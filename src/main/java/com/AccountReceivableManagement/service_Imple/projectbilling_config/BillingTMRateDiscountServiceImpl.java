package com.AccountReceivableManagement.service_Imple.projectbilling_config;

import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateDiscountRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateDiscountResponseDto;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateCard;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateDiscount;
import com.AccountReceivableManagement.entity_enums.common.LockResourceType;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingConfigurationRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateDiscountRepository;
import com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingTMRateDiscountService;
import com.AccountReceivableManagement.util.TmRateCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Discount periods of a T&amp;M rate card. Follows the rate-card service's rules: parent billing-configuration
 * edit lock, Time &amp; Material only, and the same approval-state transition on every change.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BillingTMRateDiscountServiceImpl implements BillingTMRateDiscountService {

    private final BillingTMRateDiscountRepository discountRepository;
    private final BillingConfigurationRepository billingConfigurationRepository;
    private final RecordActionLockService recordActionLockService;

    @Override
    public BillingTMRateDiscountResponseDto addDiscount(UUID rateCardId, BillingTMRateDiscountRequestDto request) {
        BillingTMRateCard rateCard = discountRepository.lockRateCard(rateCardId)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException("Rate Card not found."));
        BillingConfiguration configuration = prepareChange(rateCard);

        validate(rateCard, configuration, request, null);

        LocalDateTime now = LocalDateTime.now();
        BillingTMRateDiscount saved = discountRepository.save(BillingTMRateDiscount.builder()
                .rateCard(rateCard)
                .periodName(request.getPeriodName().trim())
                .discountType(request.getDiscountType())
                .discountValue(request.getDiscountValue())
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveTo(request.getEffectiveTo())
                .notes(request.getNotes())
                .includeInInvoice(request.getIncludeInInvoice() == null || request.getIncludeInInvoice())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build());

        touchConfiguration(configuration);
        return toResponse(saved);
    }

    @Override
    public BillingTMRateDiscountResponseDto updateDiscount(UUID discountId, BillingTMRateDiscountRequestDto request) {
        BillingTMRateDiscount discount = findActive(discountId);
        BillingTMRateCard rateCard = discountRepository.lockRateCard(discount.getRateCard().getRateCardId())
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException("Rate Card not found."));
        BillingConfiguration configuration = prepareChange(rateCard);

        validate(rateCard, configuration, request, discountId);

        discount.setPeriodName(request.getPeriodName().trim());
        discount.setDiscountType(request.getDiscountType());
        discount.setDiscountValue(request.getDiscountValue());
        discount.setEffectiveFrom(request.getEffectiveFrom());
        discount.setEffectiveTo(request.getEffectiveTo());
        discount.setNotes(request.getNotes());
        discount.setIncludeInInvoice(request.getIncludeInInvoice() == null || request.getIncludeInInvoice());
        discount.setUpdatedAt(LocalDateTime.now());

        BillingTMRateDiscount saved = discountRepository.save(discount);
        touchConfiguration(configuration);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingTMRateDiscountResponseDto> getDiscounts(UUID rateCardId) {
        return discountRepository.findByRateCard_RateCardIdAndIsActiveTrueOrderByEffectiveFromAsc(rateCardId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BillingTMRateDiscountResponseDto getDiscount(UUID discountId) {
        return toResponse(findActive(discountId));
    }

    @Override
    public void deleteDiscount(UUID discountId) {
        BillingTMRateDiscount discount = findActive(discountId);
        BillingTMRateCard rateCard = discountRepository.lockRateCard(discount.getRateCard().getRateCardId())
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException("Rate Card not found."));
        BillingConfiguration configuration = prepareChange(rateCard);

        // Soft delete, like rate cards: snapshots already taken keep their own frozen copy.
        discount.setIsActive(false);
        discount.setUpdatedAt(LocalDateTime.now());
        discountRepository.save(discount);
        touchConfiguration(configuration);
    }

    private BillingTMRateDiscount findActive(UUID discountId) {
        BillingTMRateDiscount discount = discountRepository.findById(discountId)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException("Discount period not found."));
        if (!Boolean.TRUE.equals(discount.getIsActive())) {
            throw new GlobalExceptionHandler.ResourceNotFoundException("Discount period not found.");
        }
        return discount;
    }

    private BillingConfiguration prepareChange(BillingTMRateCard rateCard) {
        BillingConfiguration configuration = rateCard.getBillingConfiguration();
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION, configuration.getBillingConfigurationId());
        if (configuration.getBillingType() == null
                || !configuration.getBillingType().getBillingTypeName().trim().equalsIgnoreCase("Timesheet Based")) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount periods can only be created for Time & Material billing.");
        }
        if (!Boolean.TRUE.equals(rateCard.getIsActive())) {
            throw new GlobalExceptionHandler.ValidationException("The rate card is not active.");
        }
        return configuration;
    }

    private void touchConfiguration(BillingConfiguration configuration) {
        BillingTMRateCardServiceImpl.handleApprovalStateTransition(configuration);
        configuration.setUpdatedAt(LocalDateTime.now());
        billingConfigurationRepository.save(configuration);
    }

    private void validate(BillingTMRateCard rateCard, BillingConfiguration configuration,
                          BillingTMRateDiscountRequestDto request, UUID excludeDiscountId) {
        validateCurrency(configuration, request.getCurrencyCode());

        // A fixed discount is in the base rate's own billing unit; hours-based billing has no documented
        // conversion rule for any other unit, so such rate cards cannot carry discounts.
        if (rateCard.getRatePeriod() != BillingRatePeriod.HOURLY) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discounts are supported only on HOURLY rate cards; this rate card is "
                            + rateCard.getRatePeriod() + ".");
        }

        LocalDate from = request.getEffectiveFrom();
        LocalDate to = request.getEffectiveTo();
        if (from.isAfter(to)) {
            throw new GlobalExceptionHandler.ValidationException("Effective From cannot be after Effective To.");
        }
        if (rateCard.getEffectiveFrom() != null && from.isBefore(rateCard.getEffectiveFrom())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount period cannot start before the rate card's Effective From (" + rateCard.getEffectiveFrom() + ").");
        }
        if (rateCard.getEffectiveTo() != null && to.isAfter(rateCard.getEffectiveTo())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount period cannot end after the rate card's Effective To (" + rateCard.getEffectiveTo() + ").");
        }
        var project = configuration.getProject();
        if (project != null && project.getStartDate() != null && from.isBefore(project.getStartDate())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount period cannot start before the Project Start Date (" + project.getStartDate() + ").");
        }
        if (project != null && project.getEndDate() != null && to.isAfter(project.getEndDate())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount period cannot end after the Project End Date (" + project.getEndDate() + ").");
        }

        // Validates type/value and that the net rate stays above zero.
        TmRateCalculator.apply(rateCard.getRate(), request.getDiscountType(), request.getDiscountValue());

        List<BillingTMRateDiscount> overlapping =
                discountRepository.findOverlapping(rateCard.getRateCardId(), from, to, excludeDiscountId);
        if (!overlapping.isEmpty()) {
            BillingTMRateDiscount other = overlapping.get(0);
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount period overlaps the existing period '" + other.getPeriodName() + "' ("
                            + other.getEffectiveFrom() + " to " + other.getEffectiveTo() + ").");
        }
    }

    /** The billing configuration's currency is authoritative; it must exist and be an active, supported one. */
    private String validateCurrency(BillingConfiguration configuration, String requestedCurrency) {
        var currency = configuration.getCurrency();
        if (currency == null || currency.getCurrencyCode() == null || currency.getCurrencyCode().isBlank()
                || !Boolean.TRUE.equals(currency.getIsActive())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "The billing configuration has no valid active currency.");
        }
        String code = currency.getCurrencyCode().trim();
        if (requestedCurrency != null && !requestedCurrency.isBlank()
                && !code.equalsIgnoreCase(requestedCurrency.trim())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount currency " + requestedCurrency.trim()
                            + " does not match the project's billing currency " + code + ".");
        }
        return code;
    }

    private BillingTMRateDiscountResponseDto toResponse(BillingTMRateDiscount discount) {
        BillingTMRateCard rateCard = discount.getRateCard();
        var currency = rateCard.getBillingConfiguration().getCurrency();
        TmRateCalculator.RateResult result = TmRateCalculator.apply(
                rateCard.getRate(), discount.getDiscountType(), discount.getDiscountValue());
        return BillingTMRateDiscountResponseDto.builder()
                .discountId(discount.getDiscountId())
                .rateCardId(rateCard.getRateCardId())
                .periodName(discount.getPeriodName())
                .discountType(discount.getDiscountType())
                .discountValue(discount.getDiscountValue())
                .effectiveFrom(discount.getEffectiveFrom())
                .effectiveTo(discount.getEffectiveTo())
                .notes(discount.getNotes())
                .includeInInvoice(discount.getIncludeInInvoice())
                .currencyCode(currency != null ? currency.getCurrencyCode() : null)
                .ratePeriod(rateCard.getRatePeriod())
                .baseRate(result.baseRate())
                .discountAmount(result.discountAmount())
                .netRate(result.netRate())
                .createdAt(discount.getCreatedAt())
                .updatedAt(discount.getUpdatedAt())
                .build();
    }
}
