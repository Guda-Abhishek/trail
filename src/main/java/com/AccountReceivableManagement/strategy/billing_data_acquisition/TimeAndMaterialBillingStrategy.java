package com.AccountReceivableManagement.strategy.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingAcquisitionResultDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotCreateRequestDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.TimesheetDto;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateCard;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateDiscount;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.PricingModel;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateDiscountRepository;
import com.AccountReceivableManagement.util.TmRateCalculator;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingType;
import com.AccountReceivableManagement.integration.billing_data_acquisition.TimesheetIntegration;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateCardRepository;
import com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto;
import com.AccountReceivableManagement.integration.billing_data_acquisition.ExpenseIntegration;
import com.AccountReceivableManagement.integration.expense_management.XmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Acquires approved timesheets from TMS for Time & Material billing
 * and resolves each entry's net hourly rate (standard base rate less any dated discount period)
 * from billing_tm_rate_card / billing_tm_rate_discount for the configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeAndMaterialBillingStrategy implements BillingAcquisitionStrategy {

    private final TimesheetIntegration timesheetIntegration;
    private final BillingTMRateCardRepository billingTMRateCardRepository;

    // Optional, field-injected so the two-argument construction used by
    // existing callers/tests keeps working (expenses simply stay off).
    // Optional as well: without it (older two-argument construction) no discount period is applied.
    @Autowired(required = false)
    private BillingTMRateDiscountRepository billingTMRateDiscountRepository;

    @Autowired(required = false)
    private ExpenseIntegration expenseIntegration;

    @Autowired(required = false)
    private XmsProperties xmsProperties;

    @Override
    public BillingType getSupportedBillingType() {
        return BillingType.TIME_AND_MATERIAL;
    }

    @Override
    public BillingAcquisitionResultDto acquire(BillingConfigurationResponseDto configuration, BillingSnapshotCreateRequestDto request) {
        List<TimesheetDto> timesheets = timesheetIntegration.getApprovedTimesheets(
                request.getProjectId(), request.getBillingPeriodStart(), request.getBillingPeriodEnd());

        if (timesheets == null) {
            timesheets = Collections.emptyList();
        }

        UUID configId = request.getBillingConfigurationId() != null
                ? request.getBillingConfigurationId()
                : (configuration != null ? configuration.getBillingConfigurationId() : null);

        for (TimesheetDto timesheet : timesheets) {
            LocalDate workDate = timesheet.getWorkDate() != null ? timesheet.getWorkDate() : request.getBillingPeriodStart();
            applyRate(timesheet, configuration, configId, workDate);
        }

        return BillingAcquisitionResultDto.builder()
                .billingConfigurationId(configId)
                .timesheets(timesheets)
                .expenses(acquireExpenses(configuration, request))
                .expensesChecked(configuration != null && configuration.isExpenseEligible())
                .build();
    }

    /**
     * Resolves the standard base rate for the work date, applies the discount period containing it (if any)
     * and records the whole calculation on the timesheet. {@code hourlyRate} becomes the net rate.
     *
     * STANDARD (or unspecified) pricing uses only role-less rate cards, falling back to the configuration's
     * hourly rate. ROLE_BASED pricing uses only the rate card whose role matches the timesheet's role - a
     * missing match is an error, never a fallback to another role's (or the role-less) rate.
     */
    private void applyRate(TimesheetDto timesheet, BillingConfigurationResponseDto configuration,
                           UUID configId, LocalDate workDate) {
        boolean roleBased = configuration != null && configuration.getPricingModel() == PricingModel.ROLE_BASED;

        List<BillingTMRateCard> candidates = configId == null ? List.of()
                : billingTMRateCardRepository.findActiveRatesByConfigurationAndDate(configId, workDate);
        if (candidates == null) {
            candidates = List.of();
        }

        BillingTMRateCard card;
        if (roleBased) {
            String role = timesheet.getRole();
            if (role == null || role.isBlank()) {
                throw new GlobalExceptionHandler.ValidationException("Timesheet ["
                        + timesheet.getSourceReferenceId() + "] has no role, which role-based billing requires.");
            }
            card = candidates.stream()
                    .filter(c -> c.getRoleName() != null && c.getRoleName().trim().equalsIgnoreCase(role.trim()))
                    .findFirst()
                    .orElseThrow(() -> new GlobalExceptionHandler.ValidationException(
                            "No active rate found for role '" + role.trim() + "' on " + workDate
                                    + " in billing configuration " + configId + "."));
        } else {
            card = candidates.stream()
                    .filter(c -> c.getRoleName() == null || c.getRoleName().isBlank())
                    .findFirst()
                    .orElse(null);
        }

        BigDecimal baseRate;
        if (card != null) {
            if (card.getRatePeriod() != null && card.getRatePeriod() != BillingRatePeriod.HOURLY) {
                throw new GlobalExceptionHandler.ValidationException("The rate for " + workDate
                        + " is a " + card.getRatePeriod() + " rate; timesheet billing needs an HOURLY rate "
                        + "(no billing-unit conversion is defined).");
            }
            baseRate = card.getRate();
        } else if (configuration != null && configuration.getHourlyRate() != null) {
            baseRate = configuration.getHourlyRate();
        } else {
            timesheet.setHourlyRate(null);
            return;
        }

        BillingTMRateDiscount discount = card == null ? null : resolveDiscount(card, workDate);
        TmRateCalculator.RateResult result = discount == null
                ? TmRateCalculator.apply(baseRate, null, null)
                : TmRateCalculator.apply(baseRate, discount.getDiscountType(), discount.getDiscountValue());

        timesheet.setBaseRate(result.baseRate());
        timesheet.setHourlyRate(result.netRate());
        timesheet.setRatePeriod(BillingRatePeriod.HOURLY.name());
        timesheet.setCurrencyCode(configuration != null ? configuration.getCurrencyCode() : null);
        if (discount == null) {
            // Base-rate work: governed by the rate card's own setting, independent of any discount period.
            timesheet.setIncludeInInvoice(card == null
                    || TmRateCalculator.isIncluded(card.getIncludeBaseRateInInvoice()));
        } else {
            timesheet.setDiscountType(discount.getDiscountType().name());
            timesheet.setDiscountValue(discount.getDiscountValue());
            timesheet.setDiscountAmount(result.discountAmount());
            timesheet.setDiscountPeriodId(String.valueOf(discount.getDiscountId()));
            timesheet.setDiscountPeriodName(discount.getPeriodName());
            timesheet.setDiscountPeriodStart(discount.getEffectiveFrom());
            timesheet.setDiscountPeriodEnd(discount.getEffectiveTo());
            timesheet.setIncludeInInvoice(discount.getIncludeInInvoice());
        }
    }

    private BillingTMRateDiscount resolveDiscount(BillingTMRateCard card, LocalDate workDate) {
        if (billingTMRateDiscountRepository == null || card.getRateCardId() == null) {
            return null;
        }
        List<BillingTMRateDiscount> active =
                billingTMRateDiscountRepository.findActiveByRateCardAndDate(card.getRateCardId(), workDate);
        if (active == null || active.isEmpty()) {
            return null;
        }
        if (active.size() > 1) {
            throw new GlobalExceptionHandler.ValidationException(
                    "More than one discount period applies on " + workDate + "; the rate schedule is ambiguous.");
        }
        return active.get(0);
    }

    /**
     * Expenses are an additional source only when the billing configuration
     * is expense-eligible. When it is, XMS must be enabled: a snapshot that
     * silently omitted required expenses would under-bill, so the
     * acquisition fails with an actionable message instead.
     */
    private List<ExpenseLineDto> acquireExpenses(BillingConfigurationResponseDto configuration,
                                                 BillingSnapshotCreateRequestDto request) {
        if (configuration == null || !configuration.isExpenseEligible()) {
            return Collections.emptyList();
        }
        if (expenseIntegration == null || xmsProperties == null || !xmsProperties.isEnabled()) {
            log.error("[ExpenseBilling] projectId={} is expense-eligible but the XMS integration is disabled.",
                    request.getProjectId());
            throw new com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler.ValidationException(
                    "Expense billing is enabled for this billing configuration, but the Expense Management (XMS) "
                            + "integration is disabled (xms.api.enabled / XMS_ENABLED). Enable it or turn off expense "
                            + "billing for this configuration; the snapshot was not created.");
        }
        return expenseIntegration.getBillableExpenses(request.getProjectId(),
                request.getBillingPeriodStart(), request.getBillingPeriodEnd(),
                configuration.getCurrencyCode());
    }
}
