package com.AccountReceivableManagement.service_Imple.projectbilling_config;

import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateCardRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateCardResponseDto;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateCard;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateDiscount;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateDiscountRepository;
import com.AccountReceivableManagement.util.TmRateCalculator;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.ApprovalStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingConfigurationStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingConfigurationRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateCardRepository;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingTMRateCardService;
import com.AccountReceivableManagement.entity_enums.common.LockResourceType;
import com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BillingTMRateCardServiceImpl implements BillingTMRateCardService {

    private final BillingTMRateCardRepository billingTMRateCardRepository;
    private final BillingTMRateDiscountRepository billingTMRateDiscountRepository;
    private final BillingConfigurationRepository billingConfigurationRepository;
    private final RecordActionLockService recordActionLockService;

    @Override
    public BillingTMRateCardResponseDto addRateCard(
            UUID billingConfigurationId,
            BillingTMRateCardRequestDto request) {

        BillingConfiguration configuration =
                billingConfigurationRepository.findById(billingConfigurationId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Billing Configuration not found."));

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                billingConfigurationId
        );

        validateBillingConfiguration(configuration);

        validateRequest(request);

        // Validate effective dates against project duration
        validateEffectiveDatesAgainstProjectDuration(
                configuration,
                request.getEffectiveFrom(),
                request.getEffectiveTo());

        BillingTMRateCard rateCard =
                BillingTMRateCard.builder()
                        .billingConfiguration(configuration)
                        .roleName(request.getRoleName())
                        .rate(request.getRate())
                        .ratePeriod(request.getRatePeriod())
                        .effectiveFrom(request.getEffectiveFrom())
                        .effectiveTo(request.getEffectiveTo())
                        .remarks(request.getRemarks())
                        .includeBaseRateInInvoice(
                                request.getIncludeBaseRateInInvoice() == null
                                        || request.getIncludeBaseRateInInvoice())
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

        BillingTMRateCard saved =
                billingTMRateCardRepository.save(rateCard);

        return mapToResponse(saved);
    }

    @Override
    public BillingTMRateCardResponseDto updateRateCard(
            UUID rateCardId,
            BillingTMRateCardRequestDto request) {

        BillingTMRateCard rateCard =
                billingTMRateCardRepository.findById(rateCardId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Rate Card not found."));

        BillingConfiguration configuration =
                rateCard.getBillingConfiguration();

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                configuration.getBillingConfigurationId()
        );

        validateBillingConfiguration(configuration);

        validateRequest(request);

        // Validate effective dates against project duration
        validateEffectiveDatesAgainstProjectDuration(
                configuration,
                request.getEffectiveFrom(),
                request.getEffectiveTo());

        // Revalidate the active discount periods against the new base rate BEFORE anything is mutated, so a
        // rejected edit changes nothing (existing discounts are never altered or deleted here).
        validateDiscountsAgainstNewBaseRate(rateCard, request);

        rateCard.setRoleName(request.getRoleName());
        rateCard.setRate(request.getRate());
        rateCard.setRatePeriod(request.getRatePeriod());
        rateCard.setEffectiveFrom(request.getEffectiveFrom());
        rateCard.setEffectiveTo(request.getEffectiveTo());
        rateCard.setRemarks(request.getRemarks());
        if (request.getIncludeBaseRateInInvoice() != null) {
            rateCard.setIncludeBaseRateInInvoice(request.getIncludeBaseRateInInvoice());
        }
        rateCard.setUpdatedAt(LocalDateTime.now());

        BillingTMRateCard updated =
                billingTMRateCardRepository.save(rateCard);

        // Handle approval state transition for the parent configuration
        handleApprovalStateTransition(configuration);
        configuration.setUpdatedAt(LocalDateTime.now());
        billingConfigurationRepository.save(configuration);

        return mapToResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingTMRateCardResponseDto getRateCard(
            UUID rateCardId) {

        BillingTMRateCard rateCard =
                billingTMRateCardRepository.findById(rateCardId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Rate Card not found."));

        return mapToResponse(rateCard);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingTMRateCardResponseDto> getAllRateCards(
            UUID billingConfigurationId) {

        BillingConfiguration configuration =
                billingConfigurationRepository.findById(billingConfigurationId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Billing Configuration not found."));

        return billingTMRateCardRepository
                .findByBillingConfigurationAndIsActiveTrueOrderByRoleNameAsc(configuration)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public void deleteRateCard(
            UUID rateCardId) {

        BillingTMRateCard rateCard =
                billingTMRateCardRepository.findById(rateCardId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Rate Card not found."));

        BillingConfiguration configuration =
                rateCard.getBillingConfiguration();

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                configuration.getBillingConfigurationId()
        );

        validateBillingConfiguration(configuration);

        rateCard.setIsActive(false);
        rateCard.setUpdatedAt(LocalDateTime.now());

        billingTMRateCardRepository.save(rateCard);
    }

    @Override
    public BillingTMRateCardResponseDto saveRateCard(
            UUID billingConfigurationId,
            BillingTMRateCardRequestDto request) {

        // Check if rate card already exists for this configuration
        BillingConfiguration configuration =
                billingConfigurationRepository.findById(billingConfigurationId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Billing Configuration not found."));

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                billingConfigurationId
        );

        validateBillingConfiguration(configuration);

        validateRequest(request);

        // Validate effective dates against project duration
        validateEffectiveDatesAgainstProjectDuration(
                configuration,
                request.getEffectiveFrom(),
                request.getEffectiveTo());

        // Check if a rate card with the same role name already exists
        if (request.getRoleName() != null && !request.getRoleName().isBlank()) {
            boolean exists = billingTMRateCardRepository
                    .existsByBillingConfigurationAndRoleNameIgnoreCaseAndIsActiveTrue(
                            configuration,
                            request.getRoleName());

            if (exists) {
                throw new GlobalExceptionHandler.ValidationException(
                        "A rate card with this role name already exists for this billing configuration.");
            }
        }

        BillingTMRateCard rateCard =
                BillingTMRateCard.builder()
                        .billingConfiguration(configuration)
                        .roleName(request.getRoleName())
                        .rate(request.getRate())
                        .ratePeriod(request.getRatePeriod())
                        .effectiveFrom(request.getEffectiveFrom())
                        .effectiveTo(request.getEffectiveTo())
                        .remarks(request.getRemarks())
                        .includeBaseRateInInvoice(
                                request.getIncludeBaseRateInInvoice() == null
                                        || request.getIncludeBaseRateInInvoice())
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

        BillingTMRateCard saved =
                billingTMRateCardRepository.save(rateCard);

        return mapToResponse(saved);
    }

    private void validateDiscountsAgainstNewBaseRate(
            BillingTMRateCard rateCard,
            BillingTMRateCardRequestDto request) {

        List<BillingTMRateDiscount> discounts = billingTMRateDiscountRepository
                .findByRateCard_RateCardIdAndIsActiveTrueOrderByEffectiveFromAsc(rateCard.getRateCardId());
        if (discounts == null || discounts.isEmpty()) {
            return;
        }
        // Discounts exist only on HOURLY rate cards (no billing-unit conversion is defined).
        if (request.getRatePeriod() != BillingRatePeriod.HOURLY) {
            throw new GlobalExceptionHandler.ValidationException(
                    "The rate period cannot be changed to " + request.getRatePeriod()
                            + " while the rate card has active discount periods; remove them first.");
        }
        for (BillingTMRateDiscount discount : discounts) {
            try {
                TmRateCalculator.apply(request.getRate(), discount.getDiscountType(), discount.getDiscountValue());
            } catch (GlobalExceptionHandler.ValidationException ex) {
                throw new GlobalExceptionHandler.ValidationException(
                        "The new rate would invalidate the discount period '" + discount.getPeriodName()
                                + "' (" + discount.getEffectiveFrom() + " to " + discount.getEffectiveTo()
                                + "): " + ex.getMessage());
            }
        }
    }

    private void validateBillingConfiguration(
            BillingConfiguration configuration) {

        if (configuration.getBillingType() == null ||
                !configuration.getBillingType()
                        .getBillingTypeName()
                        .trim()
                        .equalsIgnoreCase("Timesheet Based")) {

            throw new GlobalExceptionHandler.ValidationException(
                    "Time & Material Rate Cards can only be created for Time & Material billing.");
        }

        // Removed validation that blocked editing approved configurations.
        // Approved configurations can now be edited, which will trigger
        // the approval state transition to PENDING_APPROVAL + INACTIVE.
    }

    private void validateRequest(
            BillingTMRateCardRequestDto request) {

        if (request.getEffectiveFrom() != null &&
                request.getEffectiveTo() != null &&
                request.getEffectiveFrom()
                        .isAfter(request.getEffectiveTo())) {

            throw new GlobalExceptionHandler.ValidationException(
                    "Effective From cannot be after Effective To.");
        }
    }

    private void validateEffectiveDatesAgainstProjectDuration(
            BillingConfiguration configuration,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {

        var project = configuration.getProject();
        LocalDate projectStart = project.getStartDate();
        LocalDate projectEnd = project.getEndDate();

        // If project dates are not available, skip validation
        if (projectStart == null || projectEnd == null) {
            return;
        }

        // Validate effectiveFrom is not before project start
        if (effectiveFrom != null && effectiveFrom.isBefore(projectStart)) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Effective From date cannot be before Project Start Date ("
                    + projectStart + ").");
        }

        // Validate effectiveTo is not after project end
        if (effectiveTo != null && effectiveTo.isAfter(projectEnd)) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Effective To date cannot be after Project End Date ("
                    + projectEnd + ").");
        }
    }

    private BillingTMRateCardResponseDto mapToResponse(
            BillingTMRateCard rateCard) {

        return BillingTMRateCardResponseDto.builder()
                .rateCardId(rateCard.getRateCardId())
                .roleName(rateCard.getRoleName())
                .rate(rateCard.getRate())
                .ratePeriod(rateCard.getRatePeriod())
                .effectiveFrom(rateCard.getEffectiveFrom())
                .effectiveTo(rateCard.getEffectiveTo())
                .remarks(rateCard.getRemarks())
                .includeBaseRateInInvoice(TmRateCalculator.isIncluded(rateCard.getIncludeBaseRateInInvoice()))
                .createdAt(rateCard.getCreatedAt())
                .updatedAt(rateCard.getUpdatedAt())
                .build();
    }

    /**
     * Handles approval state transitions when a billing configuration is edited.
     * 
     * Workflow:
     * - DRAFT → Edit → DRAFT (stay in draft)
     * - PENDING_APPROVAL → Edit → PENDING_APPROVAL (stay pending)
     * - APPROVED + ACTIVE → Edit → PENDING_APPROVAL + INACTIVE (require re-approval)
     * - REJECTED → Edit → DRAFT (allow correction)
     * 
     * @param configuration The billing configuration being edited
     */
    static void handleApprovalStateTransition(BillingConfiguration configuration) {
        ApprovalStatus currentStatus = configuration.getApprovalStatus();
        
        switch (currentStatus) {
            case DRAFT:
                // Stay in DRAFT - no change needed
                break;
                
            case PENDING_APPROVAL:
                // Stay in PENDING_APPROVAL - already waiting for approval
                break;
                
            case APPROVED:
                // APPROVED + ACTIVE → PENDING_APPROVAL + INACTIVE
                // This requires re-approval after editing
                configuration.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
                configuration.setBillingStatus(BillingConfigurationStatus.INACTIVE);
                configuration.setManuallyDeactivated(false);
                configuration.setRejectionReason(null);
                break;
                
            case REJECTED:
                // REJECTED → DRAFT (allow correction and resubmission)
                configuration.setApprovalStatus(ApprovalStatus.DRAFT);
                configuration.setBillingStatus(BillingConfigurationStatus.INACTIVE);
                configuration.setRejectionReason(null);
                break;
        }
    }
}
