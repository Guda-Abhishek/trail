package com.AccountReceivableManagement.service_Imple.invoice_generation;

import com.AccountReceivableManagement.dto.company_profile.CompanyProfileResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceApprovalHistoryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceApprovalSummaryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceApprovalWorkspaceResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceItemResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceNonFinancialCorrectionRequestDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceRejectionRequestDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceSummaryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceTaxComponentResponseDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity.invoice_generation.Invoice;
import com.AccountReceivableManagement.entity.invoice_generation.InvoiceApprovalHistory;
import com.AccountReceivableManagement.entity.invoice_generation.InvoiceItem;
import com.AccountReceivableManagement.entity.invoice_generation.InvoiceTaxComponent;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingSchedule;
import com.AccountReceivableManagement.entity.projectbilling_config.PaymentTermsMaster;
import com.AccountReceivableManagement.entity.tax_calculation.TaxCalculation;
import com.AccountReceivableManagement.entity.tax_calculation.TaxCalculationComponent;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.AccountReceivableManagement.entity_enums.common.LockResourceType;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceApprovalAction;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingPeriodStatus;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxCalculationStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceApprovalHistoryRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingScheduleRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.PaymentTermsMasterRepository;
import com.AccountReceivableManagement.repo.tax_calculation.TaxCalculationRepository;
import com.AccountReceivableManagement.service_interface.company_profile.CompanyProfileService;
import com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService;
import com.AccountReceivableManagement.service_interface.invoice_generation.InvoiceService;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingConfigurationService;
import com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Generates the frozen, final Invoice for an already tax-completed
 * {@link BillingSnapshot}. Consumes the existing, persisted
 * {@link TaxCalculation} rather than recalculating it: {@code taxableAmount},
 * {@code totalTaxAmount} and {@code grandTotal} are copied as-is and are
 * never recomputed here. Invoice line items and tax components are likewise
 * copied verbatim from the snapshot's {@link BillingSnapshotItem}s and the
 * tax calculation's {@link TaxCalculationComponent}s - no TMS re-query, no
 * new {@link BillingSnapshot}, no call back into the tax engine.
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional
public class InvoiceServiceImpl implements InvoiceService {

    private static final DateTimeFormatter INVOICE_NUMBER_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * No authenticated-user context exists anywhere in this backend yet
     * (confirmed against the existing Billing Approval flow in
     * {@code BillingConfigurationServiceImpl}, which likewise records no
     * approver identity). This reuses the same literal convention already
     * used for {@code BillingSnapshot.createdBy}.
     */
    private static final String SYSTEM_ACTION_BY = "SYSTEM";

    /**
     * Recorded as the {@code SUBMITTED} history comment only when a
     * previously {@code REJECTED} invoice is resubmitted, so the audit
     * trail distinguishes a correction resubmission from the original
     * {@code GENERATED -> PENDING_APPROVAL} submission (which keeps its
     * existing {@code null} comment).
     */
    private static final String RESUBMISSION_COMMENT =
            "Invoice resubmitted for approval after correction.";

    /**
     * Recorded as the {@code CORRECTED} history comment by
     * {@link #correctNonFinancialFields(UUID, InvoiceNonFinancialCorrectionRequestDto)},
     * distinct from the fixed comment {@link #refreshAfterCorrection(UUID)}
     * records, so the audit trail can tell a non-financial correction apart
     * from a financial (billing/tax) refresh even though both share the same
     * {@code InvoiceApprovalAction.CORRECTED} action and satisfy the same
     * {@code correctionRequired} check.
     */
    private static final String NON_FINANCIAL_CORRECTION_COMMENT =
            "Non-financial invoice correction completed.";

    /**
     * Matches {@code Invoice.clientName}/{@code Invoice.projectName}'s
     * {@code length = 255} column definition - kept here as an explicit
     * service-layer guard since this project has no
     * {@code MethodArgumentNotValidException} handler wired to return 400
     * for a persistence-time value-too-long failure.
     */
    private static final int CLIENT_OR_PROJECT_NAME_MAX_LENGTH = 255;

    private final InvoiceRepository invoiceRepository;

    private final InvoiceApprovalHistoryRepository invoiceApprovalHistoryRepository;

    private final BillingSnapshotRepository billingSnapshotRepository;

    private final TaxCalculationRepository taxCalculationRepository;

    private final BillingConfigurationService billingConfigurationService;

    private final PaymentTermsMasterRepository paymentTermsMasterRepository;

        private final BillingScheduleRepository billingScheduleRepository;

        private final CompanyProfileService companyProfileService;

        private final RecordActionLockService recordActionLockService;

        public InvoiceServiceImpl(
                        InvoiceRepository invoiceRepository,
                        InvoiceApprovalHistoryRepository invoiceApprovalHistoryRepository,
                        BillingSnapshotRepository billingSnapshotRepository,
                        TaxCalculationRepository taxCalculationRepository,
                        BillingConfigurationService billingConfigurationService,
                        PaymentTermsMasterRepository paymentTermsMasterRepository,
                        RecordActionLockService recordActionLockService
        ) {
                this.invoiceRepository = invoiceRepository;
                this.invoiceApprovalHistoryRepository = invoiceApprovalHistoryRepository;
                this.billingSnapshotRepository = billingSnapshotRepository;
                this.taxCalculationRepository = taxCalculationRepository;
                this.billingConfigurationService = billingConfigurationService;
                this.paymentTermsMasterRepository = paymentTermsMasterRepository;
                this.billingScheduleRepository = null;
                this.companyProfileService = null;
                this.recordActionLockService = recordActionLockService;
        }

    @Override
    public InvoiceResponseDto generateInvoice(
            UUID billingSnapshotId
    ) {

        BillingSnapshot snapshot =
                billingSnapshotRepository.findById(
                                billingSnapshotId
                        )
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Billing snapshot could not be found."
                                )
                        );

        /*
         * Checked before the lifecycle state: once an invoice exists the
         * snapshot is INVOICED, so a repeated request (double-click, retry,
         * refresh) must be told an invoice already exists rather than that
         * tax calculation is incomplete.
         */
        if (invoiceRepository
                .existsByBillingSnapshotId(
                        billingSnapshotId
                )) {

            throw new GlobalExceptionHandler
                    .DuplicateResourceException(
                    "An invoice has already been generated for this billing snapshot."
            );
        }

        if (snapshot.getStatus()
                != BillingSnapshotStatus.TAX_COMPLETED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Invoice cannot be generated because this billing snapshot has not completed tax calculation."
            );
        }

        TaxCalculation taxCalculation =
                taxCalculationRepository
                        .findByBillingSnapshotId(
                                billingSnapshotId
                        )
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "No tax calculation has been completed for this billing snapshot. Invoice cannot be generated."
                                )
                        );

        if (taxCalculation.getStatus()
                != TaxCalculationStatus.CALCULATED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Invoice cannot be generated because the tax calculation has not completed successfully."
            );
        }

        /*
         * Authority hierarchy: the persisted TaxCalculation is the sole
         * source of the invoice's financial totals. Values are copied
         * as-is, never recomputed. The only check performed here is an
         * internal consistency guard - if the tax calculation's own totals
         * don't add up, invoice generation fails loudly instead of
         * silently "correcting" or recalculating anything.
         */
        validateTaxCalculationConsistency(
                taxCalculation,
                "Tax calculation totals are inconsistent for this billing snapshot. Invoice cannot be generated."
        );

        BillingConfigurationResponseDto configuration =
                billingConfigurationService
                        .getBillingConfiguration(
                                snapshot.getBillingConfigurationId()
                        );

        /*
         * Fails loudly (ResourceNotFoundException, via
         * CompanyProfileService.getActive()) rather than generating an
         * invoice with a fabricated or missing seller identity - the same
         * "never fabricate" convention already applied to every other
         * derived invoice field.
         */
        CompanyProfileResponseDto companyProfile =
                companyProfileService.getActive();

        LocalDate invoiceDate = LocalDate.now();

        Invoice invoice =
                Invoice.builder()
                        .invoiceNumber(generateInvoiceNumber())
                        .billingSnapshotId(snapshot.getId())
                        .billingSnapshotNumber(
                                snapshot.getSnapshotNumber()
                        )
                        .taxCalculationId(
                                taxCalculation.getTaxCalculationId()
                        )
                        .clientId(snapshot.getClientId())
                        .clientName(
                                configuration.getClientName()
                        )
                        .countryCode(configuration.getCountryCode())
                        .email(configuration.getEmail())
                        .phone(configuration.getPhone())
                        .projectId(snapshot.getProjectId())
                        .projectName(
                                configuration.getProjectName()
                        )
                        .projectStartDate(
                                configuration.getProjectStartDate()
                        )
                        .projectEndDate(
                                configuration.getProjectEndDate()
                        )
                        .billingPeriodStart(
                                snapshot.getBillingPeriodStart()
                        )
                        .billingPeriodEnd(
                                snapshot.getBillingPeriodEnd()
                        )
                        .currencyCode(snapshot.getCurrencyCode())
                        .paymentTermCode(
                                snapshot.getPaymentTermCode()
                        )
                        .paymentTermName(
                                snapshot.getPaymentTermName()
                        )
                        .sellerLegalName(companyProfile.getLegalName())
                        .sellerAddressLine1(companyProfile.getAddressLine1())
                        .sellerAddressLine2(companyProfile.getAddressLine2())
                        .sellerCity(companyProfile.getCity())
                        .sellerState(companyProfile.getState())
                        .sellerPostalCode(companyProfile.getPostalCode())
                        .sellerCountry(companyProfile.getCountry())
                        .sellerGstin(companyProfile.getGstin())
                        .sellerEmail(companyProfile.getEmail())
                        .sellerPhone(companyProfile.getPhone())
                        .sellerLogoReference(companyProfile.getLogoReference())
                        .sellerSignatureReference(companyProfile.getAuthorizedSignatureReference())
                        .invoiceNotes(companyProfile.getDefaultInvoiceNotes())
                        .termsAndConditions(companyProfile.getDefaultTermsAndConditions())
                        .paymentInstructions(companyProfile.getDefaultPaymentInstructions())
                        .taxRegionCode(snapshot.getTaxRegionCode())
                        .sourceTaxJurisdictionCode(
                                snapshot.getSourceTaxJurisdictionCode()
                        )
                        .destinationTaxJurisdictionCode(
                                snapshot.getDestinationTaxJurisdictionCode()
                        )
                        .subtotal(
                                taxCalculation.getTaxableAmount()
                        )
                        .totalTaxAmount(
                                taxCalculation.getTotalTaxAmount()
                        )
                        .grandTotal(
                                taxCalculation.getGrandTotal()
                        )
                        .invoiceDate(invoiceDate)
                        .dueDate(
                                resolveDueDate(
                                        snapshot,
                                        invoiceDate
                                )
                        )
                        .generatedAt(LocalDateTime.now())
                        .status(InvoiceStatus.GENERATED)
                        .build();

        for (
                BillingSnapshotItem snapshotItem
                : snapshot.getItems()
        ) {

            if (!com.AccountReceivableManagement.util.TmRateCalculator
                    .isIncluded(snapshotItem.getIncludeInInvoice())) {
                continue;
            }

            InvoiceItem invoiceItem =
                    InvoiceItem.builder()
                            .invoice(invoice)
                            .itemType(
                                    snapshotItem.getItemType()
                            )
                            .itemName(
                                    snapshotItem.getItemName()
                            )
                            .sourceReferenceId(
                                    snapshotItem
                                            .getSourceReferenceId()
                            )
                            .quantity(
                                    snapshotItem.getQuantity()
                            )
                            .rate(snapshotItem.getRate())
                            .amount(snapshotItem.getAmount())
                            .workDate(
                                    snapshotItem.getWorkDate()
                            )
                            .role(snapshotItem.getRole())
                            .resourceName(
                                    snapshotItem.getResourceName()
                            )
                            .build();

            invoice.getItems().add(invoiceItem);
        }

        for (
                TaxCalculationComponent taxComponent
                : taxCalculation.getComponents()
        ) {

            InvoiceTaxComponent invoiceTaxComponent =
                    InvoiceTaxComponent.builder()
                            .invoice(invoice)
                            .taxTypeId(
                                    taxComponent.getTaxTypeId()
                            )
                            .taxTypeCode(
                                    taxComponent.getTaxTypeCode()
                            )
                            .taxTypeName(
                                    taxComponent.getTaxTypeName()
                            )
                            .appliedRate(
                                    taxComponent.getAppliedRate()
                            )
                            .taxAmount(
                                    taxComponent.getTaxAmount()
                            )
                            .applicabilityType(
                                    taxComponent
                                            .getApplicabilityType()
                            )
                            .build();

            invoice.getTaxComponents()
                    .add(invoiceTaxComponent);
        }

        Invoice saved;

        try {

            saved = invoiceRepository.save(invoice);

            // Flushed here so the unique constraint on billing_snapshot_id is
            // evaluated inside this try block. Without it the insert is deferred
            // to commit, where a concurrent duplicate surfaces as an untranslated
            // DataIntegrityViolationException (HTTP 500) instead of the
            // duplicate-invoice error below.
            invoiceRepository.flush();

        } catch (DataIntegrityViolationException ex) {

            throw new GlobalExceptionHandler
                    .DuplicateResourceException(
                    "An invoice has already been generated for this billing snapshot."
            );
        }

        snapshot.setStatus(
                BillingSnapshotStatus.INVOICED
        );

        billingSnapshotRepository.save(snapshot);

        return mapToResponse(saved);
    }

    @Override
    public InvoiceResponseDto generateInvoiceForSchedule(UUID billingScheduleId) {

        BillingSchedule schedule =
                billingScheduleRepository.findByIdForUpdate(billingScheduleId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Billing occurrence could not be found."
                                ));

        if (!Boolean.TRUE.equals(schedule.getIsActive())) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Invoice cannot be generated because this billing occurrence is inactive."
            );
        }

        if (schedule.getPeriodStatus() != BillingPeriodStatus.TAX_CALCULATED
                || schedule.getTaxStatus() != BillingPeriodStatus.TAX_CALCULATED) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Invoice cannot be generated because this billing occurrence has not completed tax calculation."
            );
        }

        if (Boolean.TRUE.equals(schedule.getIsInvoiced())) {
            throw new GlobalExceptionHandler.DuplicateResourceException(
                    "An invoice has already been generated for this billing occurrence."
            );
        }

        TaxCalculation taxCalculation =
                taxCalculationRepository.findByBillingScheduleId(billingScheduleId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler.ResourceNotFoundException(
                                        "No tax calculation has been completed for this billing occurrence. Invoice cannot be generated."
                                ));

        if (taxCalculation.getStatus() != TaxCalculationStatus.CALCULATED) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Invoice cannot be generated because the tax calculation has not completed successfully."
            );
        }

        if (invoiceRepository.existsByBillingScheduleId(billingScheduleId)) {
            throw new GlobalExceptionHandler.DuplicateResourceException(
                    "An invoice has already been generated for this billing occurrence."
            );
        }

        validateTaxCalculationConsistency(
                taxCalculation,
                "Tax calculation totals are inconsistent for this billing occurrence. Invoice cannot be generated."
        );

        BillingConfigurationResponseDto configuration =
                billingConfigurationService.getBillingConfiguration(
                        schedule.getBillingConfiguration().getBillingConfigurationId()
                );

        // Determine billing type from BillingConfiguration
        String billingTypeName = configuration.getBillingTypeName();
        boolean isTimeMaterial = billingTypeName != null && 
                (billingTypeName.equalsIgnoreCase("Time & Material") || 
                 billingTypeName.equalsIgnoreCase("Time and Material") ||
                 billingTypeName.equalsIgnoreCase("TIME_MATERIAL"));

        // For Time & Material, fetch the Billing Snapshot
        UUID billingSnapshotId = null;
        String billingSnapshotNumber = null;
        
        if (isTimeMaterial) {
            // T&M requires a Billing Snapshot
            BillingSnapshot snapshot = billingSnapshotRepository
                    .findByBillingConfigurationIdAndBillingPeriodStartAndBillingPeriodEnd(
                            schedule.getBillingConfiguration().getBillingConfigurationId(),
                            schedule.getPeriodStartDate(),
                            schedule.getPeriodEndDate()
                    )
                    .orElseThrow(() ->
                            new GlobalExceptionHandler.ResourceNotFoundException(
                                    "Billing Snapshot not found for Time & Material billing occurrence. Invoice cannot be generated."
                            ));
            
            if (snapshot.getStatus() != BillingSnapshotStatus.TAX_COMPLETED) {
                throw new GlobalExceptionHandler.ValidationException(
                        "Invoice cannot be generated because the billing snapshot has not completed tax calculation."
                );
            }
            
            billingSnapshotId = snapshot.getId();
            billingSnapshotNumber = snapshot.getSnapshotNumber();
        }

        // Fails loudly rather than generating an invoice with a fabricated
        // or missing seller identity - see the equivalent comment in
        // generateInvoice(UUID).
        CompanyProfileResponseDto companyProfile =
                companyProfileService.getActive();

        LocalDate invoiceDate = LocalDate.now();
        BigDecimal billingAmount = schedule.getBillingAmount();

        Invoice.InvoiceBuilder invoiceBuilder = Invoice.builder()
                        .invoiceNumber(generateInvoiceNumber())
                        .billingScheduleId(schedule.getBillingScheduleId())
                        .billingSnapshotId(billingSnapshotId)
                        .billingSnapshotNumber(billingSnapshotNumber)
                        .taxCalculationId(taxCalculation.getTaxCalculationId())
                        .clientId(configuration.getClientId())
                        .clientName(configuration.getClientName())
                        .countryCode(configuration.getCountryCode())
                        .email(configuration.getEmail())
                        .phone(configuration.getPhone())
                        .projectId(configuration.getProjectId())
                        .projectName(configuration.getProjectName())
                        .projectStartDate(configuration.getProjectStartDate())
                        .projectEndDate(configuration.getProjectEndDate())
                        .billingPeriodStart(schedule.getPeriodStartDate())
                        .billingPeriodEnd(schedule.getPeriodEndDate())
                        .currencyCode(configuration.getCurrencyCode())
                        .paymentTermCode(configuration.getPaymentTermCode())
                        .paymentTermName(configuration.getPaymentTermName())
                        .sellerLegalName(companyProfile.getLegalName())
                        .sellerAddressLine1(companyProfile.getAddressLine1())
                        .sellerAddressLine2(companyProfile.getAddressLine2())
                        .sellerCity(companyProfile.getCity())
                        .sellerState(companyProfile.getState())
                        .sellerPostalCode(companyProfile.getPostalCode())
                        .sellerCountry(companyProfile.getCountry())
                        .sellerGstin(companyProfile.getGstin())
                        .sellerEmail(companyProfile.getEmail())
                        .sellerPhone(companyProfile.getPhone())
                        .sellerLogoReference(companyProfile.getLogoReference())
                        .sellerSignatureReference(companyProfile.getAuthorizedSignatureReference())
                        .invoiceNotes(companyProfile.getDefaultInvoiceNotes())
                        .termsAndConditions(companyProfile.getDefaultTermsAndConditions())
                        .paymentInstructions(companyProfile.getDefaultPaymentInstructions())
                        // The schedule tax path does not persist the jurisdictions
                        // it compared, so only the Tax Region is frozen here.
                        .taxRegionCode(configuration.getTaxRegionCode())
                        .subtotal(taxCalculation.getTaxableAmount())
                        .totalTaxAmount(taxCalculation.getTotalTaxAmount())
                        .grandTotal(taxCalculation.getGrandTotal())
                        .invoiceDate(invoiceDate)
                        .dueDate(resolveDueDate(configuration.getPaymentTermId(), invoiceDate))
                        .generatedAt(LocalDateTime.now())
                        .status(InvoiceStatus.GENERATED);

        Invoice invoice = invoiceBuilder.build();

        // Determine item type based on billing type
        BillingItemType itemType = isTimeMaterial ? BillingItemType.TIME_MATERIAL : BillingItemType.FIXED_PRICE;
        String itemName = isTimeMaterial 
                ? "Time & Material - Period " + schedule.getPeriodNumber()
                : configuration.getBillingTypeName() + " - Period " + schedule.getPeriodNumber();

        invoice.getItems().add(
                InvoiceItem.builder()
                        .invoice(invoice)
                        .itemType(itemType)
                        .itemName(itemName)
                        .sourceReferenceId(schedule.getBillingScheduleId().toString())
                        .quantity(BigDecimal.ONE)
                        .rate(billingAmount)
                        .amount(billingAmount)
                        .build()
        );

        for (TaxCalculationComponent taxComponent : taxCalculation.getComponents()) {
            invoice.getTaxComponents().add(
                    InvoiceTaxComponent.builder()
                            .invoice(invoice)
                            .taxTypeId(taxComponent.getTaxTypeId())
                            .taxTypeCode(taxComponent.getTaxTypeCode())
                            .taxTypeName(taxComponent.getTaxTypeName())
                            .appliedRate(taxComponent.getAppliedRate())
                            .taxAmount(taxComponent.getTaxAmount())
                            .applicabilityType(taxComponent.getApplicabilityType())
                            .build()
            );
        }

        Invoice saved;
        try {
            saved = invoiceRepository.save(invoice);
        } catch (DataIntegrityViolationException ex) {
            throw new GlobalExceptionHandler.DuplicateResourceException(
                    "An invoice has already been generated for this billing occurrence."
            );
        }

        schedule.setIsInvoiced(true);
        schedule.setInvoiceDate(invoiceDate);
        schedule.setPeriodStatus(BillingPeriodStatus.INVOICED);
        billingScheduleRepository.save(schedule);

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponseDto previewInvoice(UUID billingSnapshotId) {

        BillingSnapshot snapshot =
                billingSnapshotRepository.findById(billingSnapshotId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Billing snapshot could not be found."
                                )
                        );

        java.util.Optional<Invoice> existing =
                invoiceRepository.findByBillingSnapshotId(billingSnapshotId);

        if (existing.isPresent()) {
            return mapToResponse(existing.get());
        }

        if (snapshot.getStatus() != BillingSnapshotStatus.TAX_COMPLETED) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Invoice preview is available only for a billing snapshot that has completed tax calculation."
            );
        }

        TaxCalculation taxCalculation =
                taxCalculationRepository
                        .findByBillingSnapshotId(billingSnapshotId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "No tax calculation has been completed for this billing snapshot."
                                )
                        );

        if (taxCalculation.getStatus() != TaxCalculationStatus.CALCULATED) {
            throw new GlobalExceptionHandler.ValidationException(
                    "The tax calculation for this billing snapshot has not completed successfully."
            );
        }

        validateTaxCalculationConsistency(
                taxCalculation,
                "Tax calculation totals are inconsistent for this billing snapshot."
        );

        BillingConfigurationResponseDto configuration =
                billingConfigurationService.getBillingConfiguration(
                        snapshot.getBillingConfigurationId());

        // Seller identity as generation would snapshot it. Unlike generation
        // this never fails the preview: seller fields stay null when no
        // active profile exists.
        CompanyProfileResponseDto companyProfile = null;
        try {
            if (companyProfileService != null) {
                companyProfile = companyProfileService.getActive();
            }
        } catch (GlobalExceptionHandler.ResourceNotFoundException ex) {
            // Seller fields stay null.
        }

        // One line per snapshot item, amounts copied as-is (never
        // recomputed or aggregated); ordered by work date, then resource.
        List<BillingSnapshotItem> snapshotItems =
                snapshot.getItems().stream()
                        .filter(item -> com.AccountReceivableManagement.util.TmRateCalculator
                                .isIncluded(item.getIncludeInInvoice()))
                        .sorted(java.util.Comparator
                                .comparing(
                                        BillingSnapshotItem::getWorkDate,
                                        java.util.Comparator.nullsLast(
                                                java.util.Comparator.<LocalDate>naturalOrder()))
                                .thenComparing(
                                        BillingSnapshotItem::getResourceName,
                                        java.util.Comparator.nullsLast(
                                                java.util.Comparator.<String>naturalOrder())))
                        .toList();

        List<InvoiceItemResponseDto> items =
                java.util.stream.IntStream
                        .range(0, snapshotItems.size())
                        .mapToObj(index -> {
                                BillingSnapshotItem item = snapshotItems.get(index);
                                return InvoiceItemResponseDto.builder()
                                        .lineNumber(index + 1)
                                        .itemType(item.getItemType())
                                        .itemName(item.getItemName())
                                        .sourceReferenceId(item.getSourceReferenceId())
                                        .resourceName(item.getResourceName())
                                        .quantity(item.getQuantity())
                                        .rate(item.getRate())
                                        .amount(item.getAmount())
                                        .workDate(item.getWorkDate())
                                        .role(item.getRole())
                                        .build();
                        })
                        .toList();

        List<InvoiceTaxComponentResponseDto> taxComponents =
                taxCalculation.getComponents().stream()
                        .map(component ->
                                InvoiceTaxComponentResponseDto.builder()
                                        .taxTypeId(component.getTaxTypeId())
                                        .taxTypeCode(component.getTaxTypeCode())
                                        .taxTypeName(component.getTaxTypeName())
                                        .appliedRate(component.getAppliedRate())
                                        .taxAmount(component.getTaxAmount())
                                        .applicabilityType(component.getApplicabilityType())
                                        .build())
                        .toList();

        return InvoiceResponseDto.builder()
                .generated(false)
                .billingSnapshotId(snapshot.getId())
                .billingSnapshotNumber(snapshot.getSnapshotNumber())
                .taxCalculationId(taxCalculation.getTaxCalculationId())
                .clientId(snapshot.getClientId())
                .clientName(configuration.getClientName())
                .countryCode(configuration.getCountryCode())
                .clientCountryName(configuration.getCountryName())
                .clientCountryCode(configuration.getCountryCode())
                .email(configuration.getEmail())
                .phone(configuration.getPhone())
                .projectId(snapshot.getProjectId())
                .projectName(configuration.getProjectName())
                .projectCode(configuration.getProjectCode())
                .billingPeriodStart(snapshot.getBillingPeriodStart())
                .billingPeriodEnd(snapshot.getBillingPeriodEnd())
                .currencyCode(snapshot.getCurrencyCode())
                .paymentTermCode(snapshot.getPaymentTermCode())
                .paymentTermName(snapshot.getPaymentTermName())
                .sellerLegalName(companyProfile != null ? companyProfile.getLegalName() : null)
                .sellerAddressLine1(companyProfile != null ? companyProfile.getAddressLine1() : null)
                .sellerAddressLine2(companyProfile != null ? companyProfile.getAddressLine2() : null)
                .sellerCity(companyProfile != null ? companyProfile.getCity() : null)
                .sellerState(companyProfile != null ? companyProfile.getState() : null)
                .sellerPostalCode(companyProfile != null ? companyProfile.getPostalCode() : null)
                .sellerCountry(companyProfile != null ? companyProfile.getCountry() : null)
                .sellerGstin(companyProfile != null ? companyProfile.getGstin() : null)
                .sellerEmail(companyProfile != null ? companyProfile.getEmail() : null)
                .sellerPhone(companyProfile != null ? companyProfile.getPhone() : null)
                .sellerLogoReference(companyProfile != null ? companyProfile.getLogoReference() : null)
                .sellerSignatureReference(companyProfile != null ? companyProfile.getAuthorizedSignatureReference() : null)
                .invoiceNotes(companyProfile != null ? companyProfile.getDefaultInvoiceNotes() : null)
                .termsAndConditions(companyProfile != null ? companyProfile.getDefaultTermsAndConditions() : null)
                .paymentInstructions(companyProfile != null ? companyProfile.getDefaultPaymentInstructions() : null)
                .taxRegionCode(snapshot.getTaxRegionCode())
                .sourceTaxJurisdictionCode(snapshot.getSourceTaxJurisdictionCode())
                .destinationTaxJurisdictionCode(snapshot.getDestinationTaxJurisdictionCode())
                .items(items)
                .taxComponents(taxComponents)
                .subtotal(taxCalculation.getTaxableAmount())
                .totalTaxAmount(taxCalculation.getTotalTaxAmount())
                .grandTotal(taxCalculation.getGrandTotal())
                .correctionRequired(false)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponseDto getInvoiceByBillingSnapshotId(
            UUID billingSnapshotId
    ) {

        if (!billingSnapshotRepository.existsById(
                billingSnapshotId
        )) {

            throw new GlobalExceptionHandler
                    .ResourceNotFoundException(
                    "Billing snapshot could not be found."
            );
        }

        Invoice invoice =
                invoiceRepository
                        .findByBillingSnapshotId(
                                billingSnapshotId
                        )
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "No invoice has been generated for this billing snapshot."
                                )
                        );

        return mapToResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponseDto getInvoiceById(UUID invoiceId) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        return mapToResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceSummaryResponseDto> getAllInvoices() {

        return invoiceRepository
                .findAllByOrderByGeneratedAtDesc()
                .stream()
                .map(this::mapToSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceResponseDto
    getInvoiceGenerationWorkspace() {

        List<Invoice> invoices =
                invoiceRepository.findAllByOrderByGeneratedAtDesc();

        java.util.Set<UUID> invoicedSnapshotIds =
                invoices.stream()
                        .map(Invoice::getBillingSnapshotId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());

        Map<UUID, BillingSnapshot> snapshotsById =
                billingSnapshotRepository
                        .findAllById(invoicedSnapshotIds)
                        .stream()
                        .collect(Collectors.toMap(
                                BillingSnapshot::getId, s -> s));

        List<com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto> rows =
                new java.util.ArrayList<>();

        BigDecimal readyAmount = BigDecimal.ZERO;

        // Candidates: TAX_COMPLETED and no invoice. The invoice check keeps a
        // snapshot with an existing invoice from being shown as a candidate.
        for (BillingSnapshot snapshot
                : billingSnapshotRepository
                .findAllByStatusOrderByCreatedDateDesc(
                        BillingSnapshotStatus.TAX_COMPLETED)) {

            if (invoicedSnapshotIds.contains(snapshot.getId())) {
                continue;
            }

            var row = mapCandidateToWorkspaceRow(snapshot);
            rows.add(row);

            if (row.getGrandTotal() != null) {
                readyAmount = readyAmount.add(row.getGrandTotal());
            }
        }

        // Candidates without a snapshot: schedules whose tax calculation is
        // complete (the schedule-based flow never creates a BillingSnapshot).
        // Eligibility is the lifecycle stage only - not the billing type.
        java.util.Set<UUID> invoicedScheduleIds =
                invoices.stream()
                        .map(Invoice::getBillingScheduleId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());

        for (BillingSchedule schedule
                : billingScheduleRepository
                .findByPeriodStatusAndTaxStatusAndIsActiveTrue(
                        BillingPeriodStatus.TAX_CALCULATED,
                        BillingPeriodStatus.TAX_CALCULATED)) {

            if (Boolean.TRUE.equals(schedule.getIsInvoiced())
                    || invoicedScheduleIds.contains(schedule.getBillingScheduleId())
                    || schedule.getBillingConfiguration() == null) {
                continue;
            }

            // A schedule that has a snapshot is represented by that snapshot.
            if (billingSnapshotRepository
                    .findByBillingConfigurationIdAndBillingPeriodStartAndBillingPeriodEnd(
                            schedule.getBillingConfiguration().getBillingConfigurationId(),
                            schedule.getPeriodStartDate(),
                            schedule.getPeriodEndDate())
                    .isPresent()) {
                continue;
            }

            var row = mapScheduleCandidateToWorkspaceRow(schedule);
            rows.add(row);

            if (row.getGrandTotal() != null) {
                readyAmount = readyAmount.add(row.getGrandTotal());
            }
        }

        long readyCount = rows.size();
        long generated = 0, pending = 0, approved = 0, rejected = 0;
        BigDecimal invoicedAmount = BigDecimal.ZERO;

        for (Invoice invoice : invoices) {

            rows.add(mapInvoiceToWorkspaceRow(
                    invoice,
                    snapshotsById.get(invoice.getBillingSnapshotId())));

            switch (invoice.getStatus()) {
                case GENERATED -> generated++;
                case PENDING_APPROVAL -> pending++;
                case APPROVED -> approved++;
                case REJECTED -> rejected++;
            }

            if (invoice.getStatus() != InvoiceStatus.REJECTED
                    && invoice.getGrandTotal() != null) {
                invoicedAmount = invoicedAmount.add(invoice.getGrandTotal());
            }
        }

        return com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceResponseDto.builder()
                .summary(
                        com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceSummaryDto.builder()
                                .readyForInvoiceCount(readyCount)
                                .readyForInvoiceAmount(readyAmount)
                                .generatedCount(generated)
                                .pendingApprovalCount(pending)
                                .approvedCount(approved)
                                .rejectedCount(rejected)
                                .invoicedCount(invoices.size())
                                .totalInvoicedAmount(invoicedAmount)
                                .build())
                .rows(rows)
                .build();
    }

    private com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto
    mapCandidateToWorkspaceRow(BillingSnapshot snapshot) {

        TaxCalculation tax =
                taxCalculationRepository
                        .findByBillingSnapshotId(snapshot.getId())
                        .orElse(null);

        // Client/project names live on the billing configuration, exactly as
        // generateInvoice() resolves them. Left null if it cannot be loaded.
        BillingConfigurationResponseDto configuration = null;
        try {
            configuration =
                    billingConfigurationService.getBillingConfiguration(
                            snapshot.getBillingConfigurationId());
        } catch (RuntimeException ex) {
            // Row is still returned; names are simply unavailable.
        }

        return com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto.builder()
                .workspaceStatus(
                        com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceWorkspaceStatus.READY_FOR_INVOICE)
                .snapshotId(snapshot.getId())
                .snapshotNumber(snapshot.getSnapshotNumber())
                .snapshotStatus(snapshot.getStatus())
                .clientName(configuration != null ? configuration.getClientName() : null)
                .projectName(configuration != null ? configuration.getProjectName() : null)
                .projectCode(configuration != null ? configuration.getProjectCode() : null)
                .billingType(snapshot.getBillingType())
                .billingPeriodStart(snapshot.getBillingPeriodStart())
                .billingPeriodEnd(snapshot.getBillingPeriodEnd())
                .currencyCode(snapshot.getCurrencyCode())
                .amount(tax != null ? tax.getTaxableAmount() : snapshot.getTotalAmount())
                .totalTaxAmount(tax != null ? tax.getTotalTaxAmount() : null)
                .grandTotal(tax != null ? tax.getGrandTotal() : null)
                .build();
    }

    private com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto
    mapScheduleCandidateToWorkspaceRow(BillingSchedule schedule) {

        TaxCalculation tax =
                taxCalculationRepository
                        .findByBillingScheduleId(schedule.getBillingScheduleId())
                        .orElse(null);

        BillingConfigurationResponseDto configuration = null;
        try {
            configuration =
                    billingConfigurationService.getBillingConfiguration(
                            schedule.getBillingConfiguration().getBillingConfigurationId());
        } catch (RuntimeException ex) {
            // Row is still returned; names are simply unavailable.
        }

        return com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto.builder()
                .workspaceStatus(
                        com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceWorkspaceStatus.READY_FOR_INVOICE)
                .billingScheduleId(schedule.getBillingScheduleId())
                .clientName(configuration != null ? configuration.getClientName() : null)
                .projectName(configuration != null ? configuration.getProjectName() : null)
                .projectCode(configuration != null ? configuration.getProjectCode() : null)
                .billingType(configuration != null ? configuration.getBillingTypeName() : null)
                .billingPeriodStart(schedule.getPeriodStartDate())
                .billingPeriodEnd(schedule.getPeriodEndDate())
                .currencyCode(configuration != null ? configuration.getCurrencyCode() : null)
                .amount(tax != null ? tax.getTaxableAmount() : schedule.getBillingAmount())
                .totalTaxAmount(tax != null ? tax.getTotalTaxAmount() : null)
                .grandTotal(tax != null ? tax.getGrandTotal() : null)
                .build();
    }

    private com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto
    mapInvoiceToWorkspaceRow(Invoice invoice, BillingSnapshot snapshot) {

        return com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceRowDto.builder()
                .workspaceStatus(
                        com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceWorkspaceStatus
                                .valueOf(invoice.getStatus().name()))
                .snapshotId(invoice.getBillingSnapshotId())
                .snapshotNumber(invoice.getBillingSnapshotNumber())
                .snapshotStatus(snapshot != null ? snapshot.getStatus() : null)
                .clientName(invoice.getClientName())
                .projectName(invoice.getProjectName())
                .billingType(snapshot != null ? snapshot.getBillingType() : null)
                .billingPeriodStart(invoice.getBillingPeriodStart())
                .billingPeriodEnd(invoice.getBillingPeriodEnd())
                .currencyCode(invoice.getCurrencyCode())
                .amount(invoice.getSubtotal())
                .totalTaxAmount(invoice.getTotalTaxAmount())
                .grandTotal(invoice.getGrandTotal())
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .invoiceStatus(invoice.getStatus())
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .build();
    }

    @Override
    public InvoiceResponseDto submitForApproval(
            UUID invoiceId
    ) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        InvoiceStatus previousStatus = invoice.getStatus();

        if (previousStatus != InvoiceStatus.GENERATED
                && previousStatus != InvoiceStatus.REJECTED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Only invoices with status GENERATED or REJECTED can be submitted for approval."
            );
        }

        if (previousStatus == InvoiceStatus.REJECTED
                && resolveCorrectionState(
                        invoiceId,
                        InvoiceStatus.REJECTED
                ).correctionRequired()) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Invoice must be refreshed after correction before resubmission."
            );
        }

        invoice.setStatus(InvoiceStatus.PENDING_APPROVAL);

        Invoice saved = invoiceRepository.save(invoice);

        recordHistory(
                saved.getInvoiceId(),
                previousStatus,
                InvoiceStatus.PENDING_APPROVAL,
                InvoiceApprovalAction.SUBMITTED,
                previousStatus == InvoiceStatus.REJECTED
                        ? RESUBMISSION_COMMENT
                        : null
        );

        return mapToResponse(saved);
    }

    @Override
    public InvoiceResponseDto rejectInvoice(
            UUID invoiceId,
            InvoiceRejectionRequestDto request
    ) {

        if (request.getReason() == null
                || request.getReason().isBlank()) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Rejection reason is required."
            );
        }

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        // =========================================================
        // VALIDATE APPROVAL LOCK
        // =========================================================

        recordActionLockService.validateLockOwner(
                LockResourceType.INVOICE,
                invoiceId
        );

        if (invoice.getStatus()
                != InvoiceStatus.PENDING_APPROVAL) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Only invoices pending approval can be rejected."
            );
        }

        InvoiceStatus previousStatus = invoice.getStatus();

        invoice.setStatus(InvoiceStatus.REJECTED);

        Invoice saved = invoiceRepository.save(invoice);

        recordHistory(
                saved.getInvoiceId(),
                previousStatus,
                InvoiceStatus.REJECTED,
                InvoiceApprovalAction.REJECTED,
                request.getReason()
        );

        return mapToResponse(saved);
    }

    @Override
    public InvoiceResponseDto refreshAfterCorrection(
            UUID invoiceId
    ) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        if (invoice.getStatus()
                != InvoiceStatus.REJECTED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Only rejected invoices can be refreshed after correction."
            );
        }

        BillingSnapshot snapshot =
                billingSnapshotRepository
                        .findById(invoice.getBillingSnapshotId())
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Billing snapshot could not be found."
                                )
                        );

        if (snapshot.getStatus() != BillingSnapshotStatus.TAX_COMPLETED
                && snapshot.getStatus() != BillingSnapshotStatus.INVOICED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Invoice cannot be refreshed because this billing snapshot has not completed tax calculation."
            );
        }

        TaxCalculation taxCalculation =
                taxCalculationRepository
                        .findByBillingSnapshotId(
                                invoice.getBillingSnapshotId()
                        )
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "No tax calculation has been completed for this billing snapshot. Invoice cannot be refreshed."
                                )
                        );

        if (taxCalculation.getStatus()
                != TaxCalculationStatus.CALCULATED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Invoice cannot be refreshed because tax calculation has not completed successfully."
            );
        }

        validateTaxCalculationConsistency(
                taxCalculation,
                "Tax calculation totals are inconsistent for this billing snapshot. Invoice cannot be refreshed."
        );

        BillingConfigurationResponseDto configuration =
                billingConfigurationService.getBillingConfiguration(
                        snapshot.getBillingConfigurationId()
                );

        refreshInvoiceFromAuthoritativeData(invoice, snapshot, taxCalculation, configuration);

        snapshot.setStatus(BillingSnapshotStatus.INVOICED);

        billingSnapshotRepository.save(snapshot);

        Invoice saved = invoiceRepository.save(invoice);

        recordHistory(
                saved.getInvoiceId(),
                InvoiceStatus.REJECTED,
                InvoiceStatus.REJECTED,
                InvoiceApprovalAction.CORRECTED,
                "Invoice refreshed from corrected billing/tax data."
        );

        return mapToResponse(saved);
    }

    /**
     * Re-copies every financial field, line item, and tax component onto
     * the existing, already-persisted Invoice from its authoritative
     * BillingSnapshot/TaxCalculation - the same copy-as-is population used
     * by {@link #generateInvoice(UUID)}, applied in place instead of to a
     * newly built Invoice. {@code invoiceId}, {@code invoiceNumber}, and
     * {@code status} are never touched here.
     */
    private void refreshInvoiceFromAuthoritativeData(
            Invoice invoice,
            BillingSnapshot snapshot,
            TaxCalculation taxCalculation,
            BillingConfigurationResponseDto configuration
    ) {

        invoice.setBillingSnapshotNumber(
                snapshot.getSnapshotNumber()
        );
        invoice.setTaxCalculationId(
                taxCalculation.getTaxCalculationId()
        );
        invoice.setProjectStartDate(
                configuration.getProjectStartDate()
        );
        invoice.setProjectEndDate(
                configuration.getProjectEndDate()
        );
        invoice.setBillingPeriodStart(
                snapshot.getBillingPeriodStart()
        );
        invoice.setBillingPeriodEnd(
                snapshot.getBillingPeriodEnd()
        );
        invoice.setCurrencyCode(snapshot.getCurrencyCode());
        invoice.setTaxRegionCode(snapshot.getTaxRegionCode());
        invoice.setSourceTaxJurisdictionCode(
                snapshot.getSourceTaxJurisdictionCode()
        );
        invoice.setDestinationTaxJurisdictionCode(
                snapshot.getDestinationTaxJurisdictionCode()
        );
        invoice.setPaymentTermCode(
                snapshot.getPaymentTermCode()
        );
        invoice.setPaymentTermName(
                snapshot.getPaymentTermName()
        );

        invoice.setSubtotal(
                taxCalculation.getTaxableAmount()
        );
        invoice.setTotalTaxAmount(
                taxCalculation.getTotalTaxAmount()
        );
        invoice.setGrandTotal(
                taxCalculation.getGrandTotal()
        );
        invoice.setDueDate(
                resolveDueDate(
                        snapshot,
                        invoice.getInvoiceDate()
                )
        );

        invoice.getItems().clear();

        for (
                BillingSnapshotItem snapshotItem
                : snapshot.getItems()
        ) {

            if (!com.AccountReceivableManagement.util.TmRateCalculator
                    .isIncluded(snapshotItem.getIncludeInInvoice())) {
                continue;
            }

            invoice.getItems().add(
                    InvoiceItem.builder()
                            .invoice(invoice)
                            .itemType(
                                    snapshotItem.getItemType()
                            )
                            .itemName(
                                    snapshotItem.getItemName()
                            )
                            .sourceReferenceId(
                                    snapshotItem
                                            .getSourceReferenceId()
                            )
                            .quantity(
                                    snapshotItem.getQuantity()
                            )
                            .rate(snapshotItem.getRate())
                            .amount(snapshotItem.getAmount())
                            .workDate(
                                    snapshotItem.getWorkDate()
                            )
                            .role(snapshotItem.getRole())
                            .resourceName(
                                    snapshotItem.getResourceName()
                            )
                            .build()
            );
        }

        invoice.getTaxComponents().clear();

        for (
                TaxCalculationComponent taxComponent
                : taxCalculation.getComponents()
        ) {

            invoice.getTaxComponents().add(
                    InvoiceTaxComponent.builder()
                            .invoice(invoice)
                            .taxTypeId(
                                    taxComponent.getTaxTypeId()
                            )
                            .taxTypeCode(
                                    taxComponent.getTaxTypeCode()
                            )
                            .taxTypeName(
                                    taxComponent.getTaxTypeName()
                            )
                            .appliedRate(
                                    taxComponent.getAppliedRate()
                            )
                            .taxAmount(
                                    taxComponent.getTaxAmount()
                            )
                            .applicabilityType(
                                    taxComponent
                                            .getApplicabilityType()
                            )
                            .build()
            );
        }
    }

    @Override
    public InvoiceResponseDto correctNonFinancialFields(
            UUID invoiceId,
            InvoiceNonFinancialCorrectionRequestDto request
    ) {

        String clientName = validateAndTrimName(
                request.getClientName(),
                "Client name is required.",
                "Client name must not exceed "
                        + CLIENT_OR_PROJECT_NAME_MAX_LENGTH
                        + " characters."
        );

        String projectName = validateAndTrimName(
                request.getProjectName(),
                "Project name is required.",
                "Project name must not exceed "
                        + CLIENT_OR_PROJECT_NAME_MAX_LENGTH
                        + " characters."
        );

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        if (invoice.getStatus()
                != InvoiceStatus.REJECTED) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Only rejected invoices can be corrected."
            );
        }

        if (!resolveCorrectionState(
                invoiceId,
                InvoiceStatus.REJECTED
        ).correctionRequired()) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Invoice has already been corrected since its latest rejection. Wait for a new rejection before correcting again."
            );
        }

        invoice.setClientName(clientName);
        invoice.setProjectName(projectName);

        Invoice saved = invoiceRepository.save(invoice);

        recordHistory(
                saved.getInvoiceId(),
                InvoiceStatus.REJECTED,
                InvoiceStatus.REJECTED,
                InvoiceApprovalAction.CORRECTED,
                NON_FINANCIAL_CORRECTION_COMMENT
        );

        return mapToResponse(saved);
    }

    /**
     * Shared blank/length guard for {@code clientName}/{@code projectName}
     * in {@link #correctNonFinancialFields(UUID, InvoiceNonFinancialCorrectionRequestDto)} -
     * mirrors the manual validation style already used by
     * {@link #rejectInvoice(UUID, InvoiceRejectionRequestDto)} rather than
     * relying on {@code @Valid} binding.
     */
    private String validateAndTrimName(
            String value,
            String blankMessage,
            String tooLongMessage
    ) {

        if (value == null || value.isBlank()) {

            throw new GlobalExceptionHandler
                    .ValidationException(blankMessage);
        }

        String trimmed = value.trim();

        if (trimmed.length() > CLIENT_OR_PROJECT_NAME_MAX_LENGTH) {

            throw new GlobalExceptionHandler
                    .ValidationException(tooLongMessage);
        }

        return trimmed;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCorrectionRequired(
            UUID invoiceId
    ) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        return resolveCorrectionState(
                invoiceId,
                invoice.getStatus()
        ).correctionRequired();
    }

    @Override
    public InvoiceResponseDto approveInvoice(
            UUID invoiceId
    ) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() ->
                                new GlobalExceptionHandler
                                        .ResourceNotFoundException(
                                        "Invoice could not be found."
                                )
                        );

        // =========================================================
        // VALIDATE APPROVAL LOCK
        // =========================================================

        recordActionLockService.validateLockOwner(
                LockResourceType.INVOICE,
                invoiceId
        );

        if (invoice.getStatus()
                != InvoiceStatus.PENDING_APPROVAL) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    "Only invoices pending approval can be approved."
            );
        }

        InvoiceStatus previousStatus = invoice.getStatus();

        invoice.setStatus(InvoiceStatus.APPROVED);


        Invoice saved = invoiceRepository.save(invoice);

        recordHistory(
                saved.getInvoiceId(),
                previousStatus,
                InvoiceStatus.APPROVED,
                InvoiceApprovalAction.APPROVED,
                null
        );

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApprovalHistoryResponseDto> getApprovalHistory(
            UUID invoiceId
    ) {

        if (!invoiceRepository.existsById(invoiceId)) {

            throw new GlobalExceptionHandler
                    .ResourceNotFoundException(
                    "Invoice could not be found."
            );
        }

        return invoiceApprovalHistoryRepository
                .findByInvoiceIdOrderByActionAtAsc(invoiceId)
                .stream()
                .map(this::mapToHistoryResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApprovalSummaryResponseDto> getPendingApprovalInvoices() {

        return invoiceRepository
                .findAllByStatusOrderByGeneratedAtDesc(
                        InvoiceStatus.PENDING_APPROVAL
                )
                .stream()
                .map(this::mapToApprovalSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceApprovalWorkspaceResponseDto> getApprovalWorkspaceInvoices() {

        List<Invoice> invoices =
                invoiceRepository
                        .findAllInApprovalWorkflowOrderByGeneratedAtDesc();

        if (invoices.isEmpty()) {
            return List.of();
        }

        List<UUID> invoiceIds =
                invoices.stream()
                        .map(Invoice::getInvoiceId)
                        .toList();

        Map<UUID, List<InvoiceApprovalHistory>> historyByInvoiceId =
                invoiceApprovalHistoryRepository
                        .findByInvoiceIdInOrderByActionAtAsc(invoiceIds)
                        .stream()
                        .collect(
                                Collectors.groupingBy(
                                        InvoiceApprovalHistory::getInvoiceId
                                )
                        );

        return invoices.stream()
                .map(invoice ->
                        mapToWorkspaceSummary(
                                invoice,
                                historyByInvoiceId.getOrDefault(
                                        invoice.getInvoiceId(),
                                        List.of()
                                )
                        )
                )
                .toList();
    }

    /**
     * Defensive guard only - does not recompute anything. Fails invoice
     * generation (or, reused, invoice correction-refresh) if the persisted
     * TaxCalculation's own totals are internally inconsistent, rather than
     * silently correcting them.
     */
    private void validateTaxCalculationConsistency(
            TaxCalculation taxCalculation,
            String inconsistencyMessage
    ) {

        BigDecimal expectedGrandTotal =
                taxCalculation.getTaxableAmount()
                        .add(taxCalculation.getTotalTaxAmount());

        if (expectedGrandTotal.compareTo(
                taxCalculation.getGrandTotal()
        ) != 0) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    inconsistencyMessage
            );
        }

        // The invoice copies both the total and the components, so they
        // must agree - the breakdown has to explain the tax charged.
        BigDecimal componentTaxTotal =
                taxCalculation.getComponents()
                        .stream()
                        .map(TaxCalculationComponent::getTaxAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (componentTaxTotal.compareTo(
                taxCalculation.getTotalTaxAmount()
        ) != 0) {

            throw new GlobalExceptionHandler
                    .ValidationException(
                    inconsistencyMessage
            );
        }
    }

    private String generateInvoiceNumber() {
        return "INV-" + LocalDateTime.now()
                .format(INVOICE_NUMBER_FORMATTER);
    }

    /**
     * Resolves the due date from the billing snapshot's own (frozen)
     * payment term. Returns {@code null} - never a guessed value - when the
     * snapshot has no payment term or it cannot be resolved to a
     * {@code paymentDays} figure.
     */
    private LocalDate resolveDueDate(
            BillingSnapshot snapshot,
            LocalDate invoiceDate
    ) {

        if (snapshot.getPaymentTermId() == null) {
            return null;
        }

        return paymentTermsMasterRepository
                .findById(snapshot.getPaymentTermId())
                .map(PaymentTermsMaster::getPaymentDays)
                .map(invoiceDate::plusDays)
                .orElse(null);
    }

        private LocalDate resolveDueDate(
                        UUID paymentTermId,
                        LocalDate invoiceDate
        ) {

        if (paymentTermId == null) {
            return null;
        }

        return paymentTermsMasterRepository
                .findById(paymentTermId)
                .map(PaymentTermsMaster::getPaymentDays)
                .map(invoiceDate::plusDays)
                .orElse(null);
    }

    private InvoiceResponseDto mapToResponse(
            Invoice invoice
    ) {

        List<InvoiceItemResponseDto> items =
                java.util.stream.IntStream
                        .range(0, invoice.getItems().size())
                        .mapToObj(index -> {
                                InvoiceItem item = invoice.getItems().get(index);
                                return InvoiceItemResponseDto.builder()
                                        .invoiceItemId(
                                                item.getInvoiceItemId()
                                        )
                                        .lineNumber(index + 1)
                                        .itemType(item.getItemType())
                                        .itemName(item.getItemName())
                                        .sourceReferenceId(
                                                item
                                                        .getSourceReferenceId()
                                        )
                                        .quantity(item.getQuantity())
                                        .rate(item.getRate())
                                        .amount(item.getAmount())
                                        .workDate(item.getWorkDate())
                                        .role(item.getRole())
                                        .resourceName(
                                                item.getResourceName()
                                        )
                                        .build();
                        })
                        .toList();

        List<InvoiceTaxComponentResponseDto> taxComponents =
                invoice.getTaxComponents()
                        .stream()
                        .map(component ->
                                InvoiceTaxComponentResponseDto
                                        .builder()
                                        .invoiceTaxComponentId(
                                                component
                                                        .getInvoiceTaxComponentId()
                                        )
                                        .taxTypeId(
                                                component.getTaxTypeId()
                                        )
                                        .taxTypeCode(
                                                component
                                                        .getTaxTypeCode()
                                        )
                                        .taxTypeName(
                                                component
                                                        .getTaxTypeName()
                                        )
                                        .appliedRate(
                                                component
                                                        .getAppliedRate()
                                        )
                                        .taxAmount(
                                                component.getTaxAmount()
                                        )
                                        .applicabilityType(
                                                component
                                                        .getApplicabilityType()
                                        )
                                        .build()
                        )
                        .toList();

        CorrectionState correctionState =
                resolveCorrectionState(
                        invoice.getInvoiceId(),
                        invoice.getStatus()
                );

        return InvoiceResponseDto.builder()
                .generated(true)
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .billingSnapshotId(
                        invoice.getBillingSnapshotId()
                )
                .billingScheduleId(invoice.getBillingScheduleId())
                .billingSnapshotNumber(
                        invoice.getBillingSnapshotNumber()
                )
                .taxCalculationId(
                        invoice.getTaxCalculationId()
                )
                .clientId(invoice.getClientId())
                .clientName(invoice.getClientName())
                .countryCode(invoice.getCountryCode())
                .billingAddress(invoice.getBillingAddress())
                .gstinOrTaxId(invoice.getGstinOrTaxId())
                .contact(invoice.getContact())
                .projectId(invoice.getProjectId())
                .projectName(invoice.getProjectName())
                .projectStartDate(invoice.getProjectStartDate())
                .projectEndDate(invoice.getProjectEndDate())
                .billingPeriodStart(
                        invoice.getBillingPeriodStart()
                )
                .billingPeriodEnd(
                        invoice.getBillingPeriodEnd()
                )
                .currencyCode(invoice.getCurrencyCode())
                .paymentTermCode(invoice.getPaymentTermCode())
                .paymentTermName(invoice.getPaymentTermName())
                .email(invoice.getEmail())
                .phone(invoice.getPhone())
                .sellerLegalName(invoice.getSellerLegalName())
                .sellerAddressLine1(invoice.getSellerAddressLine1())
                .sellerAddressLine2(invoice.getSellerAddressLine2())
                .sellerCity(invoice.getSellerCity())
                .sellerState(invoice.getSellerState())
                .sellerPostalCode(invoice.getSellerPostalCode())
                .sellerCountry(invoice.getSellerCountry())
                .sellerGstin(invoice.getSellerGstin())
                .sellerEmail(invoice.getSellerEmail())
                .sellerPhone(invoice.getSellerPhone())
                .sellerLogoReference(invoice.getSellerLogoReference())
                .sellerSignatureReference(invoice.getSellerSignatureReference())
                .invoiceNotes(invoice.getInvoiceNotes())
                .termsAndConditions(invoice.getTermsAndConditions())
                .paymentInstructions(invoice.getPaymentInstructions())
                .taxRegionCode(invoice.getTaxRegionCode())
                .sourceTaxJurisdictionCode(invoice.getSourceTaxJurisdictionCode())
                .destinationTaxJurisdictionCode(invoice.getDestinationTaxJurisdictionCode())
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .items(items)
                .taxComponents(taxComponents)
                .subtotal(invoice.getSubtotal())
                .totalTaxAmount(invoice.getTotalTaxAmount())
                .grandTotal(invoice.getGrandTotal())
                .generatedAt(invoice.getGeneratedAt())
                .correctionRequired(
                        correctionState.correctionRequired()
                )
                .lastCorrectedAt(
                        correctionState.lastCorrectedAt()
                )
                .build();
    }

    /**
     * Maps one row of {@code GET /api/v1/invoices} directly from the
     * persisted Invoice - every value copied as-is, nothing recalculated.
     */
    private InvoiceSummaryResponseDto mapToSummary(
            Invoice invoice
    ) {

        return InvoiceSummaryResponseDto.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .billingSnapshotId(
                        invoice.getBillingSnapshotId()
                )
                .billingSnapshotNumber(
                        invoice.getBillingSnapshotNumber()
                )
                .clientName(invoice.getClientName())
                .projectName(invoice.getProjectName())
                .billingPeriodStart(
                        invoice.getBillingPeriodStart()
                )
                .billingPeriodEnd(
                        invoice.getBillingPeriodEnd()
                )
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .currencyCode(invoice.getCurrencyCode())
                .paymentTermCode(invoice.getPaymentTermCode())
                .subtotal(invoice.getSubtotal())
                .totalTaxAmount(invoice.getTotalTaxAmount())
                .grandTotal(invoice.getGrandTotal())
                .build();
    }

    /**
     * Appended independently of the Invoice's own save - not cascaded
     * through it - matching the standalone-audit-table convention already
     * used by {@code SoftwareBillingHistory}.
     */
    private void recordHistory(
            UUID invoiceId,
            InvoiceStatus previousStatus,
            InvoiceStatus newStatus,
            InvoiceApprovalAction action,
            String comment
    ) {

        InvoiceApprovalHistory history =
                InvoiceApprovalHistory.builder()
                        .invoiceId(invoiceId)
                        .previousStatus(previousStatus)
                        .newStatus(newStatus)
                        .action(action)
                        .actionBy(SYSTEM_ACTION_BY)
                        .actionAt(LocalDateTime.now())
                        .comment(comment)
                        .build();

        invoiceApprovalHistoryRepository.save(history);
    }

    private InvoiceApprovalHistoryResponseDto mapToHistoryResponse(
            InvoiceApprovalHistory history
    ) {

        return InvoiceApprovalHistoryResponseDto.builder()
                .action(history.getAction())
                .previousStatus(history.getPreviousStatus())
                .newStatus(history.getNewStatus())
                .actionBy(history.getActionBy())
                .actionAt(history.getActionAt())
                .comment(history.getComment())
                .build();
    }

    /**
     * Maps one row of {@code GET /api/v1/invoices/pending-approval} directly
     * from the persisted Invoice, enriched with its latest SUBMITTED
     * history entry - nothing recalculated.
     */
    private InvoiceApprovalSummaryResponseDto mapToApprovalSummary(
            Invoice invoice
    ) {

        InvoiceApprovalHistory latestSubmission =
                invoiceApprovalHistoryRepository
                        .findTopByInvoiceIdAndActionOrderByActionAtDesc(
                                invoice.getInvoiceId(),
                                InvoiceApprovalAction.SUBMITTED
                        )
                        .orElse(null);

        return InvoiceApprovalSummaryResponseDto.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .billingSnapshotId(
                        invoice.getBillingSnapshotId()
                )
                .billingSnapshotNumber(
                        invoice.getBillingSnapshotNumber()
                )
                .clientName(invoice.getClientName())
                .projectName(invoice.getProjectName())
                .billingPeriodStart(
                        invoice.getBillingPeriodStart()
                )
                .billingPeriodEnd(
                        invoice.getBillingPeriodEnd()
                )
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .currencyCode(invoice.getCurrencyCode())
                .grandTotal(invoice.getGrandTotal())
                .submittedAt(
                        latestSubmission != null
                                ? latestSubmission.getActionAt()
                                : null
                )
                .submittedBy(
                        latestSubmission != null
                                ? latestSubmission.getActionBy()
                                : null
                )
                .build();
    }

    /**
     * Maps one row of {@code GET /api/v1/invoices/approval-workspace}
     * directly from the persisted Invoice, enriched from its already
     * bulk-fetched history (ascending by actionAt) - no per-invoice query,
     * nothing recalculated.
     */
    private InvoiceApprovalWorkspaceResponseDto mapToWorkspaceSummary(
            Invoice invoice,
            List<InvoiceApprovalHistory> history
    ) {

        InvoiceApprovalHistory latestSubmission = null;
        InvoiceApprovalHistory latestAction = null;

        for (InvoiceApprovalHistory entry : history) {

            if (entry.getAction()
                    == InvoiceApprovalAction.SUBMITTED) {
                latestSubmission = entry;
            }

            latestAction = entry;
        }

        CorrectionState correctionState =
                invoice.getStatus() == InvoiceStatus.REJECTED
                        ? computeCorrectionState(history)
                        : new CorrectionState(false, null);

        return InvoiceApprovalWorkspaceResponseDto.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .billingSnapshotId(
                        invoice.getBillingSnapshotId()
                )
                .billingSnapshotNumber(
                        invoice.getBillingSnapshotNumber()
                )
                .clientName(invoice.getClientName())
                .projectName(invoice.getProjectName())
                .billingPeriodStart(
                        invoice.getBillingPeriodStart()
                )
                .billingPeriodEnd(
                        invoice.getBillingPeriodEnd()
                )
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .currencyCode(invoice.getCurrencyCode())
                .grandTotal(invoice.getGrandTotal())
                .submittedAt(
                        latestSubmission != null
                                ? latestSubmission.getActionAt()
                                : null
                )
                .submittedBy(
                        latestSubmission != null
                                ? latestSubmission.getActionBy()
                                : null
                )
                .lastAction(
                        latestAction != null
                                ? latestAction.getAction()
                                : null
                )
                .lastActionAt(
                        latestAction != null
                                ? latestAction.getActionAt()
                                : null
                )
                .correctionRequired(
                        correctionState.correctionRequired()
                )
                .lastCorrectedAt(
                        correctionState.lastCorrectedAt()
                )
                .build();
    }

    /**
     * {@code correctionRequired} is {@code true} only when the invoice is
     * currently {@code REJECTED} and no {@code CORRECTED} history entry has
     * been recorded since the latest {@code REJECTED} entry - i.e. a prior
     * correction from an earlier rejection cycle does not satisfy a newer
     * rejection (see {@link #submitForApproval(UUID)}). Fetches history
     * itself - only called for a {@code REJECTED} invoice, so this is a
     * single extra query on an already-infrequent path, never inside a bulk
     * listing.
     */
    private CorrectionState resolveCorrectionState(
            UUID invoiceId,
            InvoiceStatus status
    ) {

        if (status != InvoiceStatus.REJECTED) {
            return new CorrectionState(false, null);
        }

        return computeCorrectionState(
                invoiceApprovalHistoryRepository
                        .findByInvoiceIdOrderByActionAtAsc(invoiceId)
        );
    }

    /**
     * Pure computation over an already-fetched, ascending-by-actionAt
     * history list - used both by {@link #resolveCorrectionState(UUID,
     * InvoiceStatus)} (single-invoice paths) and by
     * {@link #mapToWorkspaceSummary(Invoice, List)} (which already
     * bulk-fetches history for every returned invoice, so no extra query is
     * incurred there).
     */
    private CorrectionState computeCorrectionState(
            List<InvoiceApprovalHistory> historyAscendingByActionAt
    ) {

        LocalDateTime latestRejectionAt = null;
        LocalDateTime latestCorrectionAt = null;

        for (
                InvoiceApprovalHistory entry
                : historyAscendingByActionAt
        ) {

            if (entry.getAction()
                    == InvoiceApprovalAction.REJECTED) {
                latestRejectionAt = entry.getActionAt();
            } else if (entry.getAction()
                    == InvoiceApprovalAction.CORRECTED) {
                latestCorrectionAt = entry.getActionAt();
            }
        }

        boolean correctionRequired =
                latestRejectionAt != null
                        && (latestCorrectionAt == null
                        || latestCorrectionAt.isBefore(
                                latestRejectionAt
                        ));

        return new CorrectionState(
                correctionRequired,
                latestCorrectionAt
        );
    }

    /**
     * {@code lastCorrectedAt} is the most recent {@code CORRECTED} entry's
     * {@code actionAt} regardless of cycle - purely informational - while
     * {@code correctionRequired} is the authoritative, cycle-aware
     * readiness flag actually enforced by {@link #submitForApproval(UUID)}.
     */
    private record CorrectionState(
            boolean correctionRequired,
            LocalDateTime lastCorrectedAt
    ) {
    }
}
