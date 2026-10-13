package com.AccountReceivableManagement.service_Imple.projectbilling_config;

import com.AccountReceivableManagement.dto.projectbilling_config.BillingMilestonePlanRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingMilestonePlanResponseDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingPaymentEntryRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingPaymentEntryResponseDto;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingMilestonePlan;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingPaymentEntry;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.ApprovalStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingConfigurationStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.PaymentStructure;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingConfigurationRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingPaymentEntryRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingMilestonePlanRepository;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingMilestonePlanService;
import com.AccountReceivableManagement.entity_enums.common.LockResourceType;
import com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class BillingMilestonePlanServiceImpl implements BillingMilestonePlanService {

    private final BillingMilestonePlanRepository billingMilestonePlanRepository;
    private final BillingPaymentEntryRepository billingPaymentEntryRepository;
    private final BillingConfigurationRepository billingConfigurationRepository;
    private final BillingOccurrenceServiceImpl billingOccurrenceService;
    private final BillingConfigurationChangeTrackingService changeTrackingService;
    private final RecordActionLockService recordActionLockService;

    @Override
    @Transactional
    public BillingMilestonePlanResponseDto create(
            UUID billingConfigurationId,
            BillingMilestonePlanRequestDto request) {

        BillingConfiguration configuration =
                billingConfigurationRepository.findById(billingConfigurationId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Billing Configuration not found."
                                ));

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                billingConfigurationId
        );

        validateBillingConfigurationForMilestonePlan(configuration);

        if (billingMilestonePlanRepository
                .existsByBillingConfigurationAndIsActiveTrue(configuration)) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Milestone Plan configuration already exists."
            );
        }

        validateRequest(request);

        BillingMilestonePlan milestonePlan =
                BillingMilestonePlan.builder()
                        .billingConfiguration(configuration)
                        .totalContractValue(request.getTotalContractValue())
                        .paymentStructure(request.getPaymentStructure())
                        .remarks(request.getRemarks())
                        .isActive(true)
                        .build();

        BillingMilestonePlan savedPlan = billingMilestonePlanRepository.save(milestonePlan);

        List<BillingPaymentEntry> entries = createEntries(
                savedPlan,
                request.getPaymentStructure(),
                request.getTotalContractValue(),
                request.getEntries()
        );

        billingPaymentEntryRepository.saveAll(entries);

        // Update parent BillingConfiguration with the actual contract value
        configuration.setContractValue(request.getTotalContractValue());
        configuration.setUpdatedAt(LocalDateTime.now());
        billingConfigurationRepository.save(configuration);

        return mapToResponse(savedPlan, entries);
    }

    @Override
    @Transactional
    public BillingMilestonePlanResponseDto update(
            UUID milestonePlanId,
            BillingMilestonePlanRequestDto request) {

        BillingMilestonePlan milestonePlan =
                billingMilestonePlanRepository.findById(milestonePlanId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Milestone Plan Configuration not found."
                                ));

        BillingConfiguration configuration =
                milestonePlan.getBillingConfiguration();

        ApprovalStatus originalApprovalStatus = configuration.getApprovalStatus();

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                configuration.getBillingConfigurationId()
        );

        validateBillingConfigurationForMilestonePlan(configuration);

        validateRequest(request);

        // Validate billing dates against project dates
        validateBillingDatesAgainstProject(configuration, request);

        // =========================================================
        // SNAPSHOT CREATION FOR APPROVED+ACTIVE EDIT
        // =========================================================
        com.AccountReceivableManagement.entity.projectbilling_config.BillingConfigurationSnapshot snapshot = null;
        List<BillingPaymentEntry> previousEntries = null;

        if (originalApprovalStatus == ApprovalStatus.APPROVED &&
            configuration.getBillingStatus() == BillingConfigurationStatus.ACTIVE) {
            log.info("Editing APPROVED+ACTIVE Milestone Plan: {}", milestonePlanId);

            // Capture previous entries for comparison
            previousEntries = billingPaymentEntryRepository
                    .findByMilestonePlanAndIsActiveTrueOrderBySequenceAsc(milestonePlan);

            // Create snapshot of the approved state
            snapshot = changeTrackingService.createSnapshot(configuration);
            log.info("Created snapshot {} for Milestone Plan configuration {}", snapshot.getSnapshotId(), configuration.getBillingConfigurationId());
        }

        // Deactivate existing entries
        List<BillingPaymentEntry> existingEntries =
                billingPaymentEntryRepository.findByMilestonePlanAndIsActiveTrue(milestonePlan);
        existingEntries.forEach(entry -> entry.setIsActive(false));
        billingPaymentEntryRepository.saveAll(existingEntries);

        // Update plan
        milestonePlan.setTotalContractValue(request.getTotalContractValue());
        milestonePlan.setPaymentStructure(request.getPaymentStructure());
        milestonePlan.setRemarks(request.getRemarks());

        BillingMilestonePlan savedPlan = billingMilestonePlanRepository.save(milestonePlan);

        // Create new entries
        List<BillingPaymentEntry> newEntries = createEntries(
                savedPlan,
                request.getPaymentStructure(),
                request.getTotalContractValue(),
                request.getEntries()
        );

        billingPaymentEntryRepository.saveAll(newEntries);

        // =========================================================
        // TRACK CHANGES FOR APPROVED+ACTIVE EDIT
        // =========================================================
        if (snapshot != null && previousEntries != null) {
            changeTrackingService.trackMilestonePlanChanges(snapshot, previousEntries, newEntries);
            log.info("Tracked milestone plan changes for snapshot {}", snapshot.getSnapshotId());
        }

        // Update parent BillingConfiguration with the actual contract value
        configuration.setContractValue(request.getTotalContractValue());

        // Handle approval state transition
        // Handle approval state transition
        handleApprovalStateTransition(configuration);

        configuration.setUpdatedAt(LocalDateTime.now());
        billingConfigurationRepository.save(configuration);

        // NOTE: Occurrence reconciliation is NOT performed here.
        // Reconciliation only happens after Finance APPROVES the pending changes
        // in the BillingConfigurationServiceImpl.approve() method.

        return mapToResponse(savedPlan, newEntries);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingMilestonePlanResponseDto get(UUID milestonePlanId) {
        BillingMilestonePlan milestonePlan =
                billingMilestonePlanRepository.findById(milestonePlanId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Milestone Plan Configuration not found."
                                ));

        List<BillingPaymentEntry> entries =
                billingPaymentEntryRepository.findByMilestonePlanAndIsActiveTrueOrderBySequenceAsc(milestonePlan);

        return mapToResponse(milestonePlan, entries);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingMilestonePlanResponseDto getByBillingConfiguration(UUID billingConfigurationId) {
        BillingConfiguration configuration =
                billingConfigurationRepository.findById(billingConfigurationId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Billing Configuration not found."
                                ));

        return findActiveByBillingConfiguration(configuration)
                .orElseThrow(() ->
                        new GlobalExceptionHandler.ResourceNotFoundException(
                                "Milestone Plan Configuration not found for this billing configuration."
                        ));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<BillingMilestonePlanResponseDto> findActiveByBillingConfiguration(
            BillingConfiguration configuration) {

        return billingMilestonePlanRepository.findByBillingConfigurationAndIsActiveTrue(configuration)
                .map(milestonePlan -> mapToResponse(
                        milestonePlan,
                        billingPaymentEntryRepository
                                .findByMilestonePlanAndIsActiveTrueOrderBySequenceAsc(milestonePlan)));
    }

    @Override
    @Transactional
    public void delete(UUID milestonePlanId) {
        BillingMilestonePlan milestonePlan =
                billingMilestonePlanRepository.findById(milestonePlanId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Milestone Plan Configuration not found."
                                ));

        BillingConfiguration configuration =
                milestonePlan.getBillingConfiguration();

        // Block if another user holds the parent BILLING_CONFIGURATION edit lock
        recordActionLockService.validateNotLockedByAnotherUser(
                LockResourceType.BILLING_CONFIGURATION,
                configuration.getBillingConfigurationId()
        );

        validateBillingConfigurationForMilestonePlan(configuration);

        milestonePlan.setIsActive(false);

        // Deactivate all entries
        List<BillingPaymentEntry> entries =
                billingPaymentEntryRepository.findByMilestonePlanAndIsActiveTrue(milestonePlan);
        entries.forEach(entry -> entry.setIsActive(false));
        billingPaymentEntryRepository.saveAll(entries);

        billingMilestonePlanRepository.save(milestonePlan);
    }

    private void validateBillingConfigurationForMilestonePlan(BillingConfiguration configuration) {
        if (configuration.getBillingType() == null ||
                configuration.getBillingType().getBillingTypeName() == null) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Billing Type is required for Milestone Plan configuration.");
        }

        String billingTypeName = configuration.getBillingType().getBillingTypeName().trim();

        if (!billingTypeName.equalsIgnoreCase("Milestone Plan")) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Milestone Plan configuration can only be created for Milestone Plan billing type. " +
                    "Current type: " + billingTypeName);
        }
    }

    private void validateRequest(BillingMilestonePlanRequestDto request) {
        if (request.getTotalContractValue() == null ||
                request.getTotalContractValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Total Contract Value must be greater than zero.");
        }

        if (request.getPaymentStructure() == null) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Payment Structure is required.");
        }

        validateEntriesForPaymentStructure(
                request.getPaymentStructure(),
                request.getEntries(),
                request.getTotalContractValue()
        );
    }

    private void validateEntriesForPaymentStructure(
            PaymentStructure paymentStructure,
            List<BillingPaymentEntryRequestDto> entries,
            BigDecimal totalContractValue) {

        if (entries == null || entries.isEmpty()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "At least one payment entry is required.");
        }

        switch (paymentStructure) {
            case FULL_PAYMENT:
                validateFullPaymentEntries(entries, totalContractValue);
                break;
            case INSTALLMENTS:
                validateInstallmentEntries(entries, totalContractValue);
                break;
        }
    }

    private void validateBillingDatesAgainstProject(
            BillingConfiguration configuration,
            BillingMilestonePlanRequestDto request) {

        // Only validate if the configuration has a project
        if (configuration.getProject() == null) {
            return;
        }

        java.time.LocalDate projectStart = configuration.getProject().getStartDate();
        java.time.LocalDate projectEnd = configuration.getProject().getEndDate();

        // If project dates are not available, skip validation
        if (projectStart == null || projectEnd == null) {
            return;
        }

        // Validate each billing date in the request
        if (request.getEntries() != null) {
            for (BillingPaymentEntryRequestDto entry : request.getEntries()) {
                if (entry.getBillingDate() != null) {
                    // Validate billing date is not before project start
                    if (entry.getBillingDate().isBefore(projectStart)) {
                        throw new GlobalExceptionHandler.ValidationException(
                                "Billing date cannot be before Project Start Date (" + projectStart + ").");
                    }

                    // Validate billing date is not after project end
                    if (entry.getBillingDate().isAfter(projectEnd)) {
                        throw new GlobalExceptionHandler.ValidationException(
                                "Billing date cannot be after Project End Date (" + projectEnd + ").");
                    }
                }
            }
        }
    }

    private void validateFullPaymentEntries(
            List<BillingPaymentEntryRequestDto> entries,
            BigDecimal totalContractValue) {

        if (entries.size() != 1) {
            throw new GlobalExceptionHandler.ValidationException(
                    "FULL_PAYMENT requires exactly one entry with 100% percentage.");
        }

        BillingPaymentEntryRequestDto entry = entries.get(0);

        if (entry.getPercentage() == null ||
                entry.getPercentage().compareTo(new BigDecimal("100")) != 0) {
            throw new GlobalExceptionHandler.ValidationException(
                    "FULL_PAYMENT entry must have exactly 100% percentage.");
        }

        if (entry.getBillingDate() == null) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Billing date is required for FULL_PAYMENT entry.");
        }
    }

    private void validateInstallmentEntries(
            List<BillingPaymentEntryRequestDto> entries,
            BigDecimal totalContractValue) {

        BigDecimal totalPercentage = BigDecimal.ZERO;

        for (BillingPaymentEntryRequestDto entry : entries) {
            if (entry.getSequence() == null || entry.getSequence() <= 0) {
                throw new GlobalExceptionHandler.ValidationException(
                        "Sequence must be greater than zero for each installment.");
            }

            if (entry.getPercentage() == null ||
                    entry.getPercentage().compareTo(BigDecimal.ZERO) <= 0) {
                throw new GlobalExceptionHandler.ValidationException(
                        "Each installment must have a percentage greater than zero.");
            }

            if (entry.getPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new GlobalExceptionHandler.ValidationException(
                        "Installment percentage cannot exceed 100%.");
            }

            if (entry.getBillingDate() == null) {
                throw new GlobalExceptionHandler.ValidationException(
                        "Billing date is required for each installment.");
            }

            totalPercentage = totalPercentage.add(entry.getPercentage());
        }

        if (totalPercentage.compareTo(new BigDecimal("100")) != 0) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Total installment percentage must equal exactly 100%. Current total: " +
                    totalPercentage + "%");
        }

        // Check for duplicate sequences
        long uniqueSequences = entries.stream()
                .map(BillingPaymentEntryRequestDto::getSequence)
                .distinct()
                .count();

        if (uniqueSequences != entries.size()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Installment sequences must be unique.");
        }
    }

    private List<BillingPaymentEntry> createEntries(
            BillingMilestonePlan milestonePlan,
            PaymentStructure paymentStructure,
            BigDecimal totalContractValue,
            List<BillingPaymentEntryRequestDto> entryRequests) {

        if (entryRequests == null || entryRequests.isEmpty()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "At least one payment entry is required.");
        }

        // Sort by sequence to ensure proper order
        List<BillingPaymentEntryRequestDto> sortedEntries = entryRequests.stream()
                .sorted(Comparator.comparing(BillingPaymentEntryRequestDto::getSequence))
                .collect(Collectors.toList());

        List<BillingPaymentEntry> entries = new ArrayList<>();
        BigDecimal allocatedAmount = BigDecimal.ZERO;

        for (int i = 0; i < sortedEntries.size(); i++) {
            BillingPaymentEntryRequestDto request = sortedEntries.get(i);
            boolean isLastEntry = (i == sortedEntries.size() - 1);

            BigDecimal amount;
            if (isLastEntry) {
                // Last entry gets the remainder to ensure total matches exactly
                amount = totalContractValue.subtract(allocatedAmount).setScale(2, RoundingMode.HALF_EVEN);
            } else {
                // Calculate amount from percentage
                amount = totalContractValue
                        .multiply(request.getPercentage())
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN);
                allocatedAmount = allocatedAmount.add(amount);
            }

            BillingPaymentEntry entry = BillingPaymentEntry.builder()
                    .milestonePlan(milestonePlan)
                    .sequence(request.getSequence())
                    .percentage(request.getPercentage())
                    .amount(amount)
                    .billingDate(request.getBillingDate())
                    .remarks(request.getRemarks())
                    .isActive(true)
                    .build();

            entries.add(entry);
        }

        return entries;
    }

    private void handleApprovalStateTransition(BillingConfiguration configuration) {
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

    private BillingMilestonePlanResponseDto mapToResponse(
            BillingMilestonePlan milestonePlan,
            List<BillingPaymentEntry> entries) {

        List<BillingPaymentEntryResponseDto> entryDtos = entries.stream()
                .map(this::mapEntryToResponse)
                .collect(Collectors.toList());

        return BillingMilestonePlanResponseDto.builder()
                .milestonePlanId(milestonePlan.getMilestonePlanId())
                .billingConfigurationId(
                        milestonePlan.getBillingConfiguration().getBillingConfigurationId()
                )
                .totalContractValue(milestonePlan.getTotalContractValue())
                .paymentStructure(milestonePlan.getPaymentStructure())
                .entries(entryDtos)
                .remarks(milestonePlan.getRemarks())
                .isActive(milestonePlan.getIsActive())
                .createdAt(milestonePlan.getCreatedAt())
                .updatedAt(milestonePlan.getUpdatedAt())
                .build();
    }

    private BillingPaymentEntryResponseDto mapEntryToResponse(BillingPaymentEntry entry) {
        return BillingPaymentEntryResponseDto.builder()
                .paymentEntryId(entry.getPaymentEntryId())
                .milestonePlanId(entry.getMilestonePlan().getMilestonePlanId())
                .sequence(entry.getSequence())
                .percentage(entry.getPercentage())
                .amount(entry.getAmount())
                .billingDate(entry.getBillingDate())
                .remarks(entry.getRemarks())
                .isActive(entry.getIsActive())
                .createdAt(entry.getCreatedAt())
                .updatedAt(entry.getUpdatedAt())
                .build();
    }

}
