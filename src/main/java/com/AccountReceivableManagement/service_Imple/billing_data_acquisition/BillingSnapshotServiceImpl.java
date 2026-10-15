package com.AccountReceivableManagement.service_Imple.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.AcquireDataResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingAcquisitionResultDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotCreateRequestDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.TimesheetDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.ValidationResultDto;
import com.AccountReceivableManagement.dto.common.ApiResponse;
import com.AccountReceivableManagement.builder.billing_data_acquisition.BillingSnapshotBuilder;
import com.AccountReceivableManagement.builder.billing_data_acquisition.BillingSnapshotBuilderContext;
import com.AccountReceivableManagement.dependency.billing_data_acquisition.ProjectMasterDataService;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingAcquisitionStatus;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingType;
import com.AccountReceivableManagement.integration.billing_data_acquisition.BillingConfigurationIntegration;
import com.AccountReceivableManagement.mapper.billing_data_acquisition.BillingSnapshotMapper;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotRepository;
import com.AccountReceivableManagement.service_interface.billing_data_acquisition.BillingAcquisitionService;
import com.AccountReceivableManagement.service_interface.billing_data_acquisition.BillingSnapshotService;
import com.AccountReceivableManagement.strategy.billing_data_acquisition.BillingAcquisitionStrategy;
import com.AccountReceivableManagement.validator.billing_data_acquisition.BillingAcquisitionValidator;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates Story 2.1's Billing Data Acquisition workflow. Coordinates
 * the existing Integration, Strategy, Validator, Builder, and Repository
 * components; contains no mapping, validation, acquisition, or persistence
 * logic of its own.
 */
@Slf4j
@Service
public class BillingSnapshotServiceImpl implements BillingSnapshotService {

    private static final DateTimeFormatter SNAPSHOT_NUMBER_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int SNAPSHOT_NUMBER_SUFFIX_LENGTH = 8;

    private final BillingSnapshotRepository billingSnapshotRepository;
    private final BillingConfigurationIntegration billingConfigurationIntegration;
    private final ProjectMasterDataService projectMasterDataService;
    private final BillingAcquisitionValidator billingAcquisitionValidator;
    private final BillingSnapshotBuilder billingSnapshotBuilder;
    private final BillingSnapshotMapper billingSnapshotMapper;
    private final com.AccountReceivableManagement.repo.projectbilling_config.CurrencyMasterRepository currencyMasterRepository;
    private final com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository projectMasterReferenceRepository;
    private final com.AccountReceivableManagement.repo.projectbilling_config.TaxRegionMasterRepository taxRegionMasterRepository;
    private final Map<BillingType, BillingAcquisitionStrategy> strategiesByBillingType;
    private final BillingAcquisitionService billingAcquisitionService;
    private final TransactionTemplate transactionTemplate;

    public BillingSnapshotServiceImpl(BillingSnapshotRepository billingSnapshotRepository,
            BillingConfigurationIntegration billingConfigurationIntegration,
            ProjectMasterDataService projectMasterDataService,
            BillingAcquisitionValidator billingAcquisitionValidator,
            BillingSnapshotBuilder billingSnapshotBuilder,
            BillingSnapshotMapper billingSnapshotMapper,
            com.AccountReceivableManagement.repo.projectbilling_config.CurrencyMasterRepository currencyMasterRepository,
            com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository projectMasterReferenceRepository,
            com.AccountReceivableManagement.repo.projectbilling_config.TaxRegionMasterRepository taxRegionMasterRepository,
            List<BillingAcquisitionStrategy> strategies,
            BillingAcquisitionService billingAcquisitionService,
            PlatformTransactionManager transactionManager) {
        this.billingSnapshotRepository = billingSnapshotRepository;
        this.billingConfigurationIntegration = billingConfigurationIntegration;
        this.projectMasterDataService = projectMasterDataService;
        this.billingAcquisitionValidator = billingAcquisitionValidator;
        this.billingSnapshotBuilder = billingSnapshotBuilder;
        this.billingSnapshotMapper = billingSnapshotMapper;
        this.currencyMasterRepository = currencyMasterRepository;
        this.projectMasterReferenceRepository = projectMasterReferenceRepository;
        this.taxRegionMasterRepository = taxRegionMasterRepository;
        this.strategiesByBillingType = strategies.stream()
                .collect(Collectors.toMap(BillingAcquisitionStrategy::getSupportedBillingType, Function.identity()));
        this.billingAcquisitionService = billingAcquisitionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ApiResponse<BillingSnapshotResponseDto> createBillingSnapshot(BillingSnapshotCreateRequestDto request) {
        if (request.getBillingPeriodStart().isAfter(request.getBillingPeriodEnd())) {
            return ApiResponse.failure("Billing period start date cannot be after end date.");
        }

        Optional<BillingSnapshot> existingOpt = findExistingSnapshot(request);
        if (existingOpt.isPresent()) {
            return reuseOrRejectExistingSnapshot(existingOpt.get(), request);
        }

        BillingConfigurationResponseDto configuration = loadApprovedBillingConfiguration(request);
        if (configuration == null || !configuration.isApproved()) {
            return ApiResponse.failure("Approved Billing Configuration not found.");
        }

        // Resolve source tax jurisdiction from BillingConfiguration
        String sourceTaxJurisdictionCode = resolveSourceTaxJurisdiction(configuration);

        // Resolve destination tax jurisdiction from ProjectMasterReference.primaryLocation
        String destinationTaxJurisdictionCode = resolveDestinationTaxJurisdiction(request.getProjectId());

        // Fallback resolution for currencyId if not set in configuration DTO
        if (configuration.getCurrencyId() == null) {
            String code = configuration.getCurrencyCode() != null ? configuration.getCurrencyCode() : "INR";
            currencyMasterRepository.findByCurrencyCodeIgnoreCase(code)
                    .ifPresent(c -> configuration.setCurrencyId(c.getCurrencyId()));

            if (configuration.getCurrencyId() == null) {
                currencyMasterRepository.findAll().stream().findFirst()
                        .ifPresent(c -> configuration.setCurrencyId(c.getCurrencyId()));
            }
        }

        BillingAcquisitionStrategy strategy = resolveStrategy(configuration.getBillingType());
        if (strategy == null) {
            return ApiResponse.failure("Billing type " + configuration.getBillingType() + " is not yet supported.");
        }

        UUID clientId = projectMasterDataService.getClientIdByProjectId(request.getProjectId());

        log.info("[BillingSnapshotStage] stage=acquire projectId={} billingConfigurationId={} period={}..{} billingType={}",
                request.getProjectId(), request.getBillingConfigurationId(),
                request.getBillingPeriodStart(), request.getBillingPeriodEnd(), configuration.getBillingType());
        BillingAcquisitionResultDto acquisitionResult = strategy.acquire(configuration, request);
        log.info("[BillingSnapshotStage] stage=acquired projectId={} timesheets={} expenses={}",
                request.getProjectId(),
                acquisitionResult != null && acquisitionResult.getTimesheets() != null
                        ? acquisitionResult.getTimesheets().size() : 0,
                acquisitionResult != null && acquisitionResult.getExpenses() != null
                        ? acquisitionResult.getExpenses().size() : 0);

        ValidationResultDto validationResult = billingAcquisitionValidator.validate(acquisitionResult);
        if (!validationResult.isSuccess()) {
            log.warn("[BillingSnapshotStage] stage=validation-failed projectId={} reason={}",
                    request.getProjectId(), validationResult.getValidationMessage());
            return ApiResponse.failure(validationResult.getValidationMessage());
        }

        BillingAmountSummary amounts = calculateAmounts(validationResult.getAcquisitionResult().getTimesheets(),
                validationResult.getAcquisitionResult().getExpenses());
        String snapshotNumber = generateSnapshotNumber();
        String createdBy = "SYSTEM";
        BillingSnapshotStatus status = BillingSnapshotStatus.READY_FOR_TAX;

        BillingSnapshotBuilderContext context = buildContext(
                configuration, request, validationResult.getAcquisitionResult(),
                clientId, snapshotNumber, createdBy, status, amounts,
                sourceTaxJurisdictionCode, destinationTaxJurisdictionCode);

        BillingSnapshot snapshot = billingSnapshotBuilder.build(context);

        try {
            // Snapshot and its acquisition record commit together or not at all,
            // so a persisted snapshot can no longer be left without its record.
            SnapshotCreation creation = transactionTemplate.execute(txStatus -> {
                BillingSnapshot savedSnapshot = persistSnapshot(snapshot);
                AcquireDataResponseDto acquisition = billingAcquisitionService.createManualAcquisition(
                        savedSnapshot.getBillingConfigurationId(),
                        savedSnapshot.getBillingPeriodStart(),
                        savedSnapshot.getBillingPeriodEnd(),
                        savedSnapshot.getId(),
                        BillingAcquisitionStatus.READY.name());
                return new SnapshotCreation(savedSnapshot, acquisition);
            });
            BillingSnapshotResponseDto responseDto = billingSnapshotMapper.toResponse(creation.snapshot(), configuration);
            responseDto.setExistingSnapshot(false);
            applyAcquisitionStatus(responseDto, creation.acquisition());
            return ApiResponse.success("Billing Snapshot created successfully.", responseDto);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request for the same project and period committed first
            // (uk_billing_snapshot_project_period). This transaction has been rolled
            // back; re-read in a fresh one and reuse the winner's snapshot.
            Optional<BillingSnapshot> concurrentSnapshot = findExistingSnapshot(request);
            if (concurrentSnapshot.isPresent()) {
                log.warn("[BillingSnapshotConcurrentCreate] Snapshot for projectId={}, period {} to {} was created "
                                + "by a concurrent request; reusing it.",
                        request.getProjectId(), request.getBillingPeriodStart(), request.getBillingPeriodEnd());
                return reuseOrRejectExistingSnapshot(concurrentSnapshot.get(), request);
            }
            log.error("[BillingSnapshotPersistenceError] Snapshot creation failed for projectId={}: {}",
                    request.getProjectId(), ex.getMostSpecificCause().getMessage(), ex);
            return ApiResponse.failure("Failed to save Billing Snapshot: " + ex.getMostSpecificCause().getMessage());
        } catch (GlobalExceptionHandler.ValidationException | GlobalExceptionHandler.ResourceNotFoundException ex) {
            // Acquisition-record validation (e.g. configuration not active) - the
            // snapshot was rolled back with it; surface the real message, as the
            // other validation failures in this method do.
            throw ex;
        } catch (Exception ex) {
            log.error("[BillingSnapshotPersistenceError] Snapshot creation failed for projectId={}: {}",
                    request.getProjectId(), ex.getMessage(), ex);
            return ApiResponse.failure("Failed to save Billing Snapshot: " + ex.getMessage());
        }
    }

    @Override
    public ApiResponse<BillingSnapshotResponseDto> getByProjectAndPeriod(Long projectId,
            LocalDate billingPeriodStart, LocalDate billingPeriodEnd) {
        return getByProjectAndPeriod(projectId, billingPeriodStart, billingPeriodEnd, null);
    }

    @Override
    public ApiResponse<BillingSnapshotResponseDto> getByProjectAndPeriod(Long projectId,
            LocalDate billingPeriodStart, LocalDate billingPeriodEnd, UUID billingConfigurationId) {
        Optional<BillingSnapshot> snapshotOptional = billingSnapshotRepository
                .findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(projectId, billingPeriodStart,
                        billingPeriodEnd);

        if (snapshotOptional.isEmpty()) {
            return ApiResponse.failure(
                    "Billing Snapshot not found for the selected project and billing period.");
        }

        BillingSnapshot snapshot = snapshotOptional.get();

        // The (project, period) key is shared by every configuration the project has
        // had, so a snapshot of another configuration is not this configuration's.
        if (billingConfigurationId != null && !billingConfigurationId.equals(snapshot.getBillingConfigurationId())) {
            log.warn("[BillingSnapshotConfigurationMismatch] snapshotId={} belongs to billingConfigurationId={}, "
                            + "requested billingConfigurationId={}; not returned.",
                    snapshot.getId(), snapshot.getBillingConfigurationId(), billingConfigurationId);
            return ApiResponse.failure(
                    "Billing Snapshot not found for the selected billing configuration and billing period.");
        }

        BillingConfigurationResponseDto configuration =
                resolveHistoricalConfiguration(snapshot.getBillingConfigurationId());

        BillingSnapshotResponseDto responseDto = billingSnapshotMapper.toResponse(snapshot, configuration);
        return ApiResponse.success("Billing Snapshot retrieved successfully.", responseDto);
    }

    /**
     * Phase 2B financial correction - see the interface Javadoc. Re-fetches
     * authoritative source data for the snapshot's own, already-known
     * {@code billingConfigurationId} and billing period, validates it with
     * the same {@link BillingAcquisitionValidator} used at first-time
     * creation, and only then mutates the existing, already-persisted
     * {@code BillingSnapshot} - never a new one. Validation failure leaves
     * the snapshot completely untouched (no field is set before validation
     * succeeds).
     */
    @Override
    @Transactional
    public BillingSnapshot rebuildBillingSnapshot(UUID billingSnapshotId) {

        BillingSnapshot snapshot = billingSnapshotRepository.findById(billingSnapshotId)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                        "Billing snapshot could not be found."));

        BillingConfigurationResponseDto configuration =
                billingConfigurationIntegration.getApprovedBillingConfigurationById(
                        snapshot.getBillingConfigurationId());

        if (configuration == null || !configuration.isApproved()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Approved Billing Configuration not found.");
        }

        BillingAcquisitionStrategy strategy = resolveStrategy(configuration.getBillingType());
        if (strategy == null) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Billing type " + configuration.getBillingType() + " is not yet supported.");
        }

        // Re-derived from the snapshot's own, already-frozen identity - never a
        // project+period search, per the correction workflow's exact-snapshot contract.
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(snapshot.getProjectId())
                .billingConfigurationId(snapshot.getBillingConfigurationId())
                .billingPeriodStart(snapshot.getBillingPeriodStart())
                .billingPeriodEnd(snapshot.getBillingPeriodEnd())
                .build();

        // The real TMS call happens here, for Time & Material - reusing the
        // existing strategy exactly as first-time acquisition does.
        BillingAcquisitionResultDto acquisitionResult = strategy.acquire(configuration, request);

        ValidationResultDto validationResult = billingAcquisitionValidator.validate(acquisitionResult);
        if (!validationResult.isSuccess()) {
            // Nothing on the snapshot has been touched yet - fail closed.
            throw new GlobalExceptionHandler.ValidationException(validationResult.getValidationMessage());
        }

        List<TimesheetDto> timesheets = validationResult.getAcquisitionResult().getTimesheets();
        keepFrozenConversions(snapshot, validationResult.getAcquisitionResult().getExpenses());
        BillingAmountSummary amounts = calculateAmounts(timesheets,
                validationResult.getAcquisitionResult().getExpenses());

        List<BillingSnapshotItem> rebuiltItems = billingSnapshotBuilder.buildItems(timesheets,
                validationResult.getAcquisitionResult().getExpenses());
        rebuiltItems.forEach(item -> item.setBillingSnapshot(snapshot));

        // Replace items in place - the managed collection's orphanRemoval mapping
        // deletes what's cleared and inserts what's added, same pattern already
        // used by InvoiceServiceImpl.refreshInvoiceFromAuthoritativeData().
        snapshot.getItems().clear();
        snapshot.getItems().addAll(rebuiltItems);

        snapshot.setSubtotal(amounts.getSubtotal());
        snapshot.setExpenseAmount(amounts.getExpenseAmount());
        snapshot.setTotalAmount(amounts.getTotalAmount());

        // Only for the correction workflow - lets the existing, unmodified
        // TaxCalculationService.calculateTax(UUID) run again for this snapshot.
        snapshot.setStatus(BillingSnapshotStatus.READY_FOR_TAX);
        snapshot.setUpdatedBy("SYSTEM");
        snapshot.setUpdatedDate(LocalDateTime.now());

        return billingSnapshotRepository.save(snapshot);
    }

    /**
     * A rebuild re-acquires expenses, which re-converts any cross-currency line. An expense the snapshot
     * already converted keeps its frozen rate and billed amount - a rebuild must never change an existing
     * conversion. It applies only while XMS still reports the same source amount and currency; anything
     * else (or a new expense) keeps the freshly acquired conversion.
     */
    private void keepFrozenConversions(BillingSnapshot snapshot,
            List<com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto> expenses) {
        if (expenses == null) {
            return;
        }
        for (com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto expense : expenses) {
            if (expense.getExchangeRate() == null) {
                continue;
            }
            snapshot.getItems().stream()
                    .filter(item -> item.getItemType() == com.AccountReceivableManagement.entity_enums
                            .billing_data_acquisition.BillingItemType.EXPENSE)
                    .filter(item -> expense.getLineItemId().equals(item.getSourceReferenceId()))
                    .filter(item -> item.getExchangeRate() != null && item.getSourceAmount() != null
                            && item.getSourceAmount().compareTo(expense.getSourceAmount()) == 0
                            && item.getSourceCurrency() != null
                            && item.getSourceCurrency().equalsIgnoreCase(expense.getSourceCurrency()))
                    .findFirst()
                    .ifPresent(item -> {
                        expense.setCostBasis(item.getAmount());
                        expense.setExchangeRate(item.getExchangeRate());
                        expense.setExchangeRateDate(item.getExchangeRateDate());
                        expense.setExchangeRateSource(item.getExchangeRateSource());
                    });
        }
    }

    private Optional<BillingSnapshot> findExistingSnapshot(BillingSnapshotCreateRequestDto request) {
        return billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                request.getProjectId(), request.getBillingPeriodStart(), request.getBillingPeriodEnd());
    }

    /**
     * Snapshots are unique per (project, period), not per configuration, so the
     * row found for a request may belong to a different - e.g. earlier,
     * since-replaced - Billing Configuration of the same project. Handing that
     * snapshot back would show the requested configuration another
     * configuration's lifecycle state (including INVOICED), so it is rejected
     * instead, naming the conflicting snapshot. A request that names no
     * configuration is checked against the project's currently approved one,
     * when that can be resolved.
     */
    private ApiResponse<BillingSnapshotResponseDto> reuseOrRejectExistingSnapshot(BillingSnapshot existing,
            BillingSnapshotCreateRequestDto request) {
        UUID requestedConfigurationId = resolveRequestedConfigurationId(request);
        if (requestedConfigurationId != null
                && !requestedConfigurationId.equals(existing.getBillingConfigurationId())) {
            log.warn("[BillingSnapshotConfigurationMismatch] projectId={}, period {} to {}: snapshotId={} ({}, {}) "
                            + "belongs to billingConfigurationId={}, requested billingConfigurationId={}; not reused.",
                    request.getProjectId(), request.getBillingPeriodStart(), request.getBillingPeriodEnd(),
                    existing.getId(), existing.getSnapshotNumber(), existing.getStatus(),
                    existing.getBillingConfigurationId(), requestedConfigurationId);
            return ApiResponse.failure("Billing Snapshot " + existing.getSnapshotNumber() + " (status "
                    + existing.getStatus() + ") already exists for this project and billing period under a "
                    + "different Billing Configuration (" + existing.getBillingConfigurationId() + "). It cannot "
                    + "be reused for the selected Billing Configuration; choose another billing period or "
                    + "resolve the earlier snapshot.");
        }
        return reuseExistingSnapshot(existing);
    }

    private UUID resolveRequestedConfigurationId(BillingSnapshotCreateRequestDto request) {
        if (request.getBillingConfigurationId() != null) {
            return request.getBillingConfigurationId();
        }
        try {
            BillingConfigurationResponseDto active =
                    billingConfigurationIntegration.getApprovedBillingConfiguration(request.getProjectId());
            return active != null ? active.getBillingConfigurationId() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Returns an already-persisted snapshot exactly as stored - same id,
     * snapshot number, totals and lifecycle status, never rebuilt - after
     * making sure its acquisition record exists and references it.
     */
    private ApiResponse<BillingSnapshotResponseDto> reuseExistingSnapshot(BillingSnapshot existing) {
        BillingConfigurationResponseDto configuration =
                resolveHistoricalConfiguration(existing.getBillingConfigurationId());
        BillingSnapshotResponseDto responseDto = billingSnapshotMapper.toResponse(existing, configuration);
        responseDto.setExistingSnapshot(true);
        applyAcquisitionStatus(responseDto, reconcileAcquisition(existing));
        return ApiResponse.success(
                "Billing Snapshot already exists for the selected project and billing period.", responseDto);
    }

    /**
     * Repairs a missing or stale acquisition record for an existing snapshot.
     * A configuration that can no longer be recorded against (deleted,
     * unapproved or inactive) must not hide the snapshot itself, so those
     * validation failures are logged and the snapshot is still returned.
     */
    private AcquireDataResponseDto reconcileAcquisition(BillingSnapshot existing) {
        try {
            return billingAcquisitionService.recordAcquisitionForSnapshot(
                    existing.getBillingConfigurationId(),
                    existing.getBillingPeriodStart(),
                    existing.getBillingPeriodEnd(),
                    existing.getId());
        } catch (GlobalExceptionHandler.ValidationException | GlobalExceptionHandler.ResourceNotFoundException ex) {
            log.warn("[BillingAcquisitionReconcileSkipped] snapshotId={}, billingConfigurationId={}: {}",
                    existing.getId(), existing.getBillingConfigurationId(), ex.getMessage());
            return null;
        }
    }

    private void applyAcquisitionStatus(BillingSnapshotResponseDto responseDto, AcquireDataResponseDto acquisition) {
        if (acquisition != null && acquisition.getStatus() != null) {
            responseDto.setAcquisitionStatus(acquisition.getStatus());
        }
    }

    private record SnapshotCreation(BillingSnapshot snapshot, AcquireDataResponseDto acquisition) {
    }

    /**
     * Resolves the Billing Configuration referenced by a snapshot's frozen,
     * historical {@code billingConfigurationId}. That id is never re-pointed
     * at the project's currently active configuration; if the historical
     * configuration has since been deleted, the snapshot itself must still
     * be returned, so only the display-enrichment fields are affected.
     */
    private BillingConfigurationResponseDto resolveHistoricalConfiguration(UUID billingConfigurationId) {
        try {
            return billingConfigurationIntegration.getApprovedBillingConfigurationById(billingConfigurationId);
        } catch (GlobalExceptionHandler.ResourceNotFoundException ex) {
            log.warn(
                    "[BillingSnapshotHistoricalConfigurationMissing] billingConfigurationId={} could not be resolved; "
                            + "returning snapshot with configuration-derived fields omitted. Reason: {}",
                    billingConfigurationId, ex.getMessage());
            return BillingConfigurationResponseDto.builder().build();
        }
    }

    private BillingConfigurationResponseDto loadApprovedBillingConfiguration(BillingSnapshotCreateRequestDto request) {
        if (request.getBillingConfigurationId() != null) {
            try {
                BillingConfigurationResponseDto config = billingConfigurationIntegration
                        .getApprovedBillingConfigurationById(request.getBillingConfigurationId());
                if (config != null) {
                    return config;
                }
            } catch (Exception ex) {
                log.warn("Lookup failed for billingConfigurationId={}, falling back to projectId lookup",
                        request.getBillingConfigurationId());
            }
        }
        return billingConfigurationIntegration.getApprovedBillingConfiguration(request.getProjectId());
    }

    private BillingAcquisitionStrategy resolveStrategy(BillingType billingType) {
        return strategiesByBillingType.get(billingType);
    }

    static BillingAmountSummary calculateAmounts(List<TimesheetDto> timesheets,
            List<com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto> expenses) {
        // Lines excluded from the invoice stay on the snapshot for audit but never reach the payable
        // subtotal, and therefore never the tax base (tax is calculated from the snapshot total).
        BigDecimal subtotal = timesheets.stream()
                .filter(timesheet -> com.AccountReceivableManagement.util.TmRateCalculator
                        .isIncluded(timesheet.getIncludeInInvoice()))
                .map(timesheet -> com.AccountReceivableManagement.util.TmRateCalculator
                        .lineAmount(timesheet.getHours(), timesheet.getHourlyRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // costBasis is already the billable base-currency amount: summed as-is.
        BigDecimal expenseAmount = expenses == null ? BigDecimal.ZERO : expenses.stream()
                .map(com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto::getCostBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return BillingAmountSummary.builder()
                .subtotal(subtotal)
                .expenseAmount(expenseAmount)
                .totalAmount(subtotal.add(expenseAmount))
                .build();
    }

    /**
     * {@code BS-yyyyMMddHHmmss-XXXXXXXX} (26 chars, fits the 30-char
     * snapshot_number columns). The random hex suffix keeps numbers unique
     * when several snapshots are created within the same second; earlier
     * {@code BS-yyyyMMddHHmmss} numbers are left as they are.
     */
    String generateSnapshotNumber() {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, SNAPSHOT_NUMBER_SUFFIX_LENGTH).toUpperCase();
        return "BS-" + LocalDateTime.now().format(SNAPSHOT_NUMBER_FORMATTER) + "-" + suffix;
    }

    private BillingSnapshotBuilderContext buildContext(BillingConfigurationResponseDto configuration,
            BillingSnapshotCreateRequestDto request,
            BillingAcquisitionResultDto acquisitionResult,
            UUID clientId,
            String snapshotNumber,
            String createdBy,
            BillingSnapshotStatus status,
            BillingAmountSummary amounts,
            String sourceTaxJurisdictionCode,
            String destinationTaxJurisdictionCode) {
        return BillingSnapshotBuilderContext.builder()
                .configuration(configuration)
                .request(request)
                .acquisitionResult(acquisitionResult)
                .clientId(clientId)
                .snapshotNumber(snapshotNumber)
                .createdBy(createdBy)
                .status(status)
                .subtotal(amounts.getSubtotal())
                .expenseAmount(amounts.getExpenseAmount())
                .totalAmount(amounts.getTotalAmount())
                .sourceTaxJurisdictionCode(sourceTaxJurisdictionCode)
                .destinationTaxJurisdictionCode(destinationTaxJurisdictionCode)
                .build();
    }

    private String resolveSourceTaxJurisdiction(BillingConfigurationResponseDto configuration) {
        if (configuration.getTaxRegionCode() == null || configuration.getTaxRegionCode().isBlank()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Tax jurisdiction could not be determined because the billing configuration has no tax region.");
        }
        return configuration.getTaxRegionCode().trim().toUpperCase();
    }

    private String resolveDestinationTaxJurisdiction(Long projectId) {
        com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference project =
                projectMasterReferenceRepository.findBypmsProjectId(projectId)
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "Project not found with id: " + projectId));

        String primaryLocation = project.getPrimaryLocation();
        if (primaryLocation == null || primaryLocation.isBlank()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Client tax jurisdiction could not be determined because project primary location is missing.");
        }

        String locationName = primaryLocation.trim();
        return taxRegionMasterRepository.findByTaxRegionNameIgnoreCase(locationName)
                .map(taxRegion -> {
                    String code = taxRegion.getTaxRegionCode();
                    if (code == null || code.isBlank()) {
                        throw new GlobalExceptionHandler.ValidationException(
                                "Tax region code is missing for client location: " + locationName);
                    }
                    return code.trim().toUpperCase();
                })
                .orElseThrow(() -> new GlobalExceptionHandler.ValidationException(
                        "No tax region found for client location: " + locationName));
    }

    private BillingSnapshot persistSnapshot(BillingSnapshot snapshot) {
        log.info(
                "[BillingSnapshotPreSave] snapshotNumber={}, billingConfigurationId={}, clientId={}, projectId={}, billingTypeId={}, billingType={}, currencyId={}, currencyCode={}, paymentTermId={}, paymentTermCode={}, billingFrequencyId={}, billingFrequency={}, taxRegionId={}, taxRegionCode={}, billingPeriodStart={}, billingPeriodEnd={}, status={}, subtotal={}, expenseAmount={}, totalAmount={}",
                snapshot.getSnapshotNumber(),
                snapshot.getBillingConfigurationId(),
                snapshot.getClientId(),
                snapshot.getProjectId(),
                snapshot.getBillingTypeId(),
                snapshot.getBillingType(),
                snapshot.getCurrencyId(),
                snapshot.getCurrencyCode(),
                snapshot.getPaymentTermId(),
                snapshot.getPaymentTermCode(),
                snapshot.getBillingFrequencyId(),
                snapshot.getBillingFrequency(),
                snapshot.getTaxRegionId(),
                snapshot.getTaxRegionCode(),
                snapshot.getBillingPeriodStart(),
                snapshot.getBillingPeriodEnd(),
                snapshot.getStatus(),
                snapshot.getSubtotal(),
                snapshot.getExpenseAmount(),
                snapshot.getTotalAmount());

        try {
            // Flushed so a unique-key conflict surfaces here, not at commit.
            return billingSnapshotRepository.saveAndFlush(snapshot);
        } catch (DataIntegrityViolationException ex) {
            // Left untranslated - the caller distinguishes a lost race from other failures.
            log.warn("[BillingSnapshotSaveConflict] snapshotNumber={}: {}",
                    snapshot.getSnapshotNumber(), ex.getMostSpecificCause().getMessage());
            throw ex;
        } catch (Exception ex) {
            Throwable rootCause = ex;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }
            log.error("[BillingSnapshotSaveFailure] Persistence failed for snapshotNumber={}. Root Cause: {}",
                    snapshot.getSnapshotNumber(), rootCause.getMessage(), ex);
            throw new IllegalStateException("Database insert error [" + rootCause.getMessage() + "]", ex);
        }
    }
}
