package com.AccountReceivableManagement.builder.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.TimesheetDto;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@link BillingSnapshotBuilderContext} into a {@link BillingSnapshot}
 * with its {@link BillingSnapshotItem}s attached. Pure mapping only — every
 * business decision (totals, status, snapshot number) is made by the Service
 * and supplied through the context; the Builder only copies and wires.
 */
@Component
public class BillingSnapshotBuilder {

    static final String EXPENSE_SOURCE_SYSTEM = "XMS";

    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Maps acquired timesheets into unattached {@link BillingSnapshotItem}s -
     * the same field mapping {@link #build(BillingSnapshotBuilderContext)}
     * uses for first-time creation, extracted so a snapshot-rebuild path
     * (Phase 2B financial correction) can reuse it verbatim against an
     * already-persisted {@link BillingSnapshot} without going through this
     * class's first-time-creation context. Callers own wiring
     * {@code item.setBillingSnapshot(...)} themselves.
     */
    public List<BillingSnapshotItem> buildItems(List<TimesheetDto> timesheets) {
        return buildItems(timesheets, List.of());
    }

    /** Timesheet lines followed by XMS expense lines (one individual line per expense). */
    public List<BillingSnapshotItem> buildItems(List<TimesheetDto> timesheets, List<ExpenseLineDto> expenses) {
        List<BillingSnapshotItem> items = new ArrayList<>();
        for (TimesheetDto timesheet : timesheets) {
            items.add(BillingSnapshotItem.builder()
                    .itemType(BillingItemType.TIME_ENTRY)
                    .itemName(timesheet.getResourceName())
                    .sourceReferenceId(timesheet.getSourceReferenceId())
                    .quantity(timesheet.getHours())
                    .rate(timesheet.getHourlyRate())
                    .amount(com.AccountReceivableManagement.util.TmRateCalculator
                            .lineAmount(timesheet.getHours(), timesheet.getHourlyRate()))
                    .workDate(timesheet.getWorkDate())
                    .approvalStatus(timesheet.getApprovalStatus())
                    .role(timesheet.getRole())
                    .resourceName(timesheet.getResourceName())
                    .baseRate(timesheet.getBaseRate())
                    .rateCurrency(timesheet.getCurrencyCode())
                    .ratePeriod(timesheet.getRatePeriod())
                    .discountType(timesheet.getDiscountType())
                    .discountValue(timesheet.getDiscountValue())
                    .discountAmount(timesheet.getDiscountAmount())
                    .discountPeriodId(timesheet.getDiscountPeriodId())
                    .discountPeriodName(timesheet.getDiscountPeriodName())
                    .discountPeriodStart(timesheet.getDiscountPeriodStart())
                    .discountPeriodEnd(timesheet.getDiscountPeriodEnd())
                    .includeInInvoice(timesheet.getIncludeInInvoice())
                    .build());
        }
        if (expenses != null) {
            expenses.forEach(expense -> items.add(buildExpenseItem(expense)));
        }
        return items;
    }

    /**
     * Presentation-neutral: one snapshot item per expense, amount = costBasis
     * as acquired - the XMS amount, or the already-converted billing-currency
     * amount (conversion happens during acquisition, never here); no tax adjustment. How
     * these lines are grouped on the invoice is decided downstream, not here.
     */
    BillingSnapshotItem buildExpenseItem(ExpenseLineDto expense) {
        String name = expense.getCategory() != null && !expense.getCategory().isBlank()
                ? expense.getCategory() : expense.getDescription();
        return BillingSnapshotItem.builder()
                .itemType(BillingItemType.EXPENSE)
                .itemName(name)
                .sourceReferenceId(expense.getLineItemId())
                .quantity(java.math.BigDecimal.ONE)
                .rate(expense.getCostBasis())
                .amount(expense.getCostBasis())
                .workDate(expense.getExpenseDate())
                .sourceSystem(EXPENSE_SOURCE_SYSTEM)
                .sourceEventId(expense.getSyncId())
                .sourceMetadata(toMetadataJson(expense))
                // Conversion audit, copied as acquired (all null for same-currency lines).
                .sourceAmount(expense.getSourceAmount())
                .sourceCurrency(expense.getSourceCurrency())
                .targetCurrency(expense.getExchangeRate() != null ? expense.getCurrency() : null)
                .exchangeRate(expense.getExchangeRate())
                .exchangeRateDate(expense.getExchangeRateDate())
                .exchangeRateSource(expense.getExchangeRateSource())
                .build();
    }

    private String toMetadataJson(ExpenseLineDto expense) {
        try {
            return METADATA_MAPPER.writeValueAsString(expense);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Expense [" + expense.getLineItemId() + "] audit data could not be serialised.", ex);
        }
    }

    public BillingSnapshot build(BillingSnapshotBuilderContext context) {
        List<TimesheetDto> timesheets = context.getAcquisitionResult().getTimesheets();

        List<BillingSnapshotItem> items = buildItems(timesheets, context.getAcquisitionResult().getExpenses());

        BillingSnapshot snapshot = BillingSnapshot.builder()
                .snapshotNumber(context.getSnapshotNumber())
                .billingConfigurationId(context.getConfiguration().getBillingConfigurationId())
                .clientId(context.getClientId())
                .projectId(context.getRequest().getProjectId())
                .billingTypeId(context.getConfiguration().getBillingTypeId())
                .billingType(context.getConfiguration().getBillingTypeName() != null ? context.getConfiguration().getBillingTypeName() : (context.getConfiguration().getBillingType() != null ? context.getConfiguration().getBillingType().name() : null))
                .currencyId(context.getConfiguration().getCurrencyId())
                .currencyCode(context.getConfiguration().getCurrencyCode())
                .paymentTermId(context.getConfiguration().getPaymentTermId())
                .paymentTermCode(context.getConfiguration().getPaymentTermCode() != null ? context.getConfiguration().getPaymentTermCode() : context.getConfiguration().getPaymentTermName())
                .paymentTermName(context.getConfiguration().getPaymentTermName())
                .billingFrequencyId(context.getConfiguration().getBillingFrequencyId())
                .billingFrequency(context.getConfiguration().getBillingFrequencyName())
                .taxRegionId(context.getConfiguration().getTaxRegionId())
                .taxRegionCode(context.getConfiguration().getTaxRegionCode())
                .sourceTaxJurisdictionCode(context.getSourceTaxJurisdictionCode())
                .destinationTaxJurisdictionCode(context.getDestinationTaxJurisdictionCode())
                .billingPeriodStart(context.getRequest().getBillingPeriodStart())
                .billingPeriodEnd(context.getRequest().getBillingPeriodEnd())
                .status(context.getStatus())
                .subtotal(context.getSubtotal())
                .expenseAmount(context.getExpenseAmount())
                .totalAmount(context.getTotalAmount())
                .items(items)
                .createdBy(context.getCreatedBy())
                .createdDate(java.time.LocalDateTime.now())
                .updatedDate(java.time.LocalDateTime.now())
                .build();

        items.forEach(item -> item.setBillingSnapshot(snapshot));

        return snapshot;
    }
}
