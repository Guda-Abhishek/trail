package com.AccountReceivableManagement.service_Imple.invoice_generation;

import com.AccountReceivableManagement.builder.billing_data_acquisition.BillingSnapshotBuilder;
import com.AccountReceivableManagement.dependency.billing_data_acquisition.ProjectMasterDataService;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.TimesheetDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceResponseDto;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity.invoice_generation.Invoice;
import com.AccountReceivableManagement.entity.invoice_generation.InvoiceApprovalHistory;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateCard;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxConfigurationComponent;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxTypeMaster;
import com.AccountReceivableManagement.entity.tax_calculation.TaxCalculation;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingType;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceApprovalAction;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxApplicabilityType;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxCalculationStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.integration.billing_data_acquisition.BillingConfigurationIntegration;
import com.AccountReceivableManagement.integration.billing_data_acquisition.TimesheetIntegration;
import com.AccountReceivableManagement.mapper.billing_data_acquisition.BillingSnapshotMapper;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceApprovalHistoryRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceRepository;
import com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingScheduleRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateCardRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.CurrencyMasterRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.PaymentTermsMasterRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.TaxConfigurationRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.TaxRegionMasterRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingConfigurationRepository;
import com.AccountReceivableManagement.repo.tax_calculation.TaxCalculationRepository;
import com.AccountReceivableManagement.service_Imple.billing_data_acquisition.BillingSnapshotServiceImpl;
import com.AccountReceivableManagement.service_Imple.tax_calculation.TaxCalculationServiceImpl;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingConfigurationService;
import com.AccountReceivableManagement.strategy.billing_data_acquisition.TimeAndMaterialBillingStrategy;
import com.AccountReceivableManagement.validator.billing_data_acquisition.BillingAcquisitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end unit test for Phase 2B's financial-correction/reacquisition
 * "first mile". Wires REAL {@link InvoiceFinancialCorrectionServiceImpl},
 * {@link BillingSnapshotServiceImpl}, {@link TaxCalculationServiceImpl}, and
 * {@link InvoiceServiceImpl} instances together - only repositories and the
 * TMS/BillingConfiguration integration boundaries are mocked - so these
 * tests prove the whole chain (TMS re-fetch -&gt; snapshot rebuild -&gt; tax
 * recalculation -&gt; existing refreshAfterCorrection()) genuinely produces
 * corrected figures, not just that individual mocks were told to return
 * them. Mirrors the same "use real business-logic classes, mock only
 * persistence/external boundaries" convention already used by
 * {@code BillingSnapshotServiceImplTest} and {@code InvoiceServiceImplTest}'s
 * full-cycle tests.
 */
/*
 * LENIENT: wireStatefulMocks() is a shared fixture rig feeding several
 * differently-shaped scenarios (full happy path, early validation failure,
 * resubmission cycle) - some legitimately never reach every stub it sets up
 * (e.g. the validation-failure test never reaches tax recalculation). Strict
 * stubbing would otherwise flag those as unused per-test even though each
 * stub is genuinely exercised by other tests sharing the same helper.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceFinancialCorrectionServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceApprovalHistoryRepository invoiceApprovalHistoryRepository;

    @Mock
    private BillingSnapshotRepository billingSnapshotRepository;

    @Mock
    private TaxCalculationRepository taxCalculationRepository;

    @Mock
    private TaxConfigurationRepository taxConfigurationRepository;

    @Mock
    private BillingConfigurationService billingConfigurationService;

    @Mock
    private BillingScheduleRepository billingScheduleRepository;

    @Mock
    private BillingConfigurationRepository billingConfigurationRepository;

    @Mock
    private BillingConfigurationIntegration billingConfigurationIntegration;

    @Mock
    private TimesheetIntegration timesheetIntegration;

    @Mock
    private BillingTMRateCardRepository billingTMRateCardRepository;

    // Unstubbed it returns no discount periods, so the existing scenarios are unaffected.
    @Mock
    private com.AccountReceivableManagement.repo.projectbilling_config.BillingTMRateDiscountRepository billingTMRateDiscountRepository;

    @Mock
    private PaymentTermsMasterRepository paymentTermsMasterRepository;

    @Mock
    private ProjectMasterDataService projectMasterDataService;

    @Mock
    private CurrencyMasterRepository currencyMasterRepository;

    @Mock
    private ProjectMasterReferenceRepository projectMasterReferenceRepository;

    @Mock
    private TaxRegionMasterRepository taxRegionMasterRepository;

    private InvoiceFinancialCorrectionServiceImpl financialCorrectionService;

    private InvoiceServiceImpl invoiceService;

    private static final Long PROJECT_ID = 23L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 31);

    private UUID invoiceId;
    private UUID snapshotId;
    private UUID configId;
    private UUID taxRegionId;
    private UUID oldTaxCalculationId;

    private TaxTypeMaster cgstType;
    private TaxTypeMaster sgstType;

    private List<InvoiceApprovalHistory> savedHistory;

    @BeforeEach
    void setUp() {
        invoiceId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        configId = UUID.randomUUID();
        taxRegionId = UUID.randomUUID();
        oldTaxCalculationId = UUID.randomUUID();

        cgstType = TaxTypeMaster.builder()
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("CGST")
                .taxTypeName("Central GST")
                .isActive(true)
                .build();
        sgstType = TaxTypeMaster.builder()
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("SGST")
                .taxTypeName("State GST")
                .isActive(true)
                .build();

        // Real business-logic collaborators, wired with mocked persistence/
        // external boundaries - the same convention already used elsewhere.
        TimeAndMaterialBillingStrategy timeAndMaterialStrategy =
                new TimeAndMaterialBillingStrategy(timesheetIntegration, billingTMRateCardRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(
                timeAndMaterialStrategy, "billingTMRateDiscountRepository", billingTMRateDiscountRepository);

        BillingSnapshotServiceImpl billingSnapshotService = new BillingSnapshotServiceImpl(
                billingSnapshotRepository,
                billingConfigurationIntegration,
                projectMasterDataService,
                new BillingAcquisitionValidator(),
                new BillingSnapshotBuilder(),
                new BillingSnapshotMapper(),
                currencyMasterRepository,
                projectMasterReferenceRepository,
                taxRegionMasterRepository,
                List.of(timeAndMaterialStrategy),
                // Not used by rebuildBillingSnapshot (the correction path).
                org.mockito.Mockito.mock(
                        com.AccountReceivableManagement.service_interface.billing_data_acquisition.BillingAcquisitionService.class),
                org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));

        TaxCalculationServiceImpl taxCalculationService = new TaxCalculationServiceImpl(
                taxCalculationRepository,
                billingSnapshotRepository,
                billingScheduleRepository,
                taxConfigurationRepository,
                taxRegionMasterRepository,
                billingConfigurationRepository);

        invoiceService = new InvoiceServiceImpl(
                invoiceRepository,
                invoiceApprovalHistoryRepository,
                billingSnapshotRepository,
                taxCalculationRepository,
                billingConfigurationService,
                paymentTermsMasterRepository,
                org.mockito.Mockito.mock(com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService.class));

        financialCorrectionService = new InvoiceFinancialCorrectionServiceImpl(
                invoiceRepository,
                invoiceService,
                billingSnapshotService,
                taxCalculationRepository,
                taxCalculationService);
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private Invoice rejectedInvoice() {
        return Invoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber("INV-20260901000000")
                .billingSnapshotId(snapshotId)
                .billingSnapshotNumber("BS-STALE")
                .taxCalculationId(oldTaxCalculationId)
                .clientId(UUID.randomUUID())
                .clientName("Acme Corp")
                .projectId(PROJECT_ID)
                .projectName("Website Redesign")
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .currencyCode("USD")
                .paymentTermCode(null)
                .subtotal(new BigDecimal("8800.00"))
                .totalTaxAmount(new BigDecimal("1584.00"))
                .grandTotal(new BigDecimal("10384.00"))
                .invoiceDate(LocalDate.of(2026, 9, 1))
                .dueDate(null)
                .generatedAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .status(InvoiceStatus.REJECTED)
                .items(new ArrayList<>())
                .taxComponents(new ArrayList<>())
                .build();
    }

    private BillingSnapshot invoicedSnapshotWithStaleItem() {
        BillingSnapshotItem staleItem = BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID())
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("Jane Doe")
                .sourceReferenceId("TMS-001")
                .quantity(new BigDecimal("11"))
                .rate(new BigDecimal("800"))
                .amount(new BigDecimal("8800"))
                .workDate(LocalDate.of(2026, 8, 10))
                .approvalStatus("APPROVED")
                .role("Developer")
                .build();

        return BillingSnapshot.builder()
                .id(snapshotId)
                .snapshotNumber("BS-STALE")
                .billingConfigurationId(configId)
                .clientId(UUID.randomUUID())
                .projectId(PROJECT_ID)
                .billingTypeId(UUID.randomUUID())
                .billingType("Timesheet Based")
                .currencyId(UUID.randomUUID())
                .currencyCode("USD")
                .taxRegionId(taxRegionId)
                .taxRegionCode("DOM")
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .status(BillingSnapshotStatus.INVOICED)
                .subtotal(new BigDecimal("8800"))
                .expenseAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("8800"))
                .items(new ArrayList<>(List.of(staleItem)))
                .createdBy("SYSTEM")
                .createdDate(LocalDateTime.of(2026, 8, 20, 10, 0))
                .build();
    }

    private TaxCalculation oldTaxCalculation() {
        return TaxCalculation.builder()
                .taxCalculationId(oldTaxCalculationId)
                .billingSnapshotId(snapshotId)
                .taxRegionId(taxRegionId)
                .taxConfigurationId(UUID.randomUUID())
                .taxableAmount(new BigDecimal("8800.00"))
                .totalTaxAmount(new BigDecimal("1584.00"))
                .grandTotal(new BigDecimal("10384.00"))
                .status(TaxCalculationStatus.CALCULATED)
                .calculatedAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .components(new ArrayList<>())
                .build();
    }

    private BillingConfigurationResponseDto approvedTimeAndMaterialConfiguration() {
        return BillingConfigurationResponseDto.builder()
                .billingConfigurationId(configId)
                .projectId(PROJECT_ID)
                .billingType(BillingType.TIME_AND_MATERIAL)
                .billingTypeName("Timesheet Based")
                .currencyCode("USD")
                .taxRegionId(taxRegionId)
                .taxRegionCode("DOM")
                .approved(true)
                .build();
    }

    private com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration
    epic1ConfigurationForTaxResponseMapping() {
        return com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration.builder()
                .billingConfigurationId(configId)
                .project(com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference.builder()
                        .projectName("Website Redesign").build())
                .client(com.AccountReceivableManagement.entity.client_entity.Client.builder()
                        .clientName("Acme Corp").build())
                .currency(com.AccountReceivableManagement.entity.projectbilling_config.CurrencyMaster.builder()
                        .currencyCode("USD").build())
                .taxRegion(com.AccountReceivableManagement.entity.projectbilling_config.TaxRegionMaster.builder()
                        .taxRegionName("Domestic (GST 18%)")
                        .taxRegionCode("DOM")
                        .build())
                .build();
    }

    private TaxConfiguration taxConfigurationCgstSgst() {
        TaxConfigurationComponent cgst = TaxConfigurationComponent.builder()
                .taxConfigurationComponentId(UUID.randomUUID())
                .taxType(cgstType)
                .taxRate(new BigDecimal("9.0000"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .isActive(true)
                .build();
        TaxConfigurationComponent sgst = TaxConfigurationComponent.builder()
                .taxConfigurationComponentId(UUID.randomUUID())
                .taxType(sgstType)
                .taxRate(new BigDecimal("9.0000"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .isActive(true)
                .build();

        TaxConfiguration configuration = TaxConfiguration.builder()
                .taxConfigurationId(UUID.randomUUID())
                .taxRegime("GST")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .isActive(true)
                .components(new ArrayList<>(List.of(cgst, sgst)))
                .build();
        cgst.setTaxConfiguration(configuration);
        sgst.setTaxConfiguration(configuration);
        return configuration;
    }

    private TimesheetDto correctedTimesheet(BigDecimal hours) {
        return TimesheetDto.builder()
                .resourceId(1L)
                .resourceName("Jane Doe")
                .sourceReferenceId("TMS-001")
                .workDate(LocalDate.of(2026, 8, 10))
                .hours(hours)
                .role("Developer")
                .approvalStatus("APPROVED")
                .approved(true)
                .billable(true)
                .build();
    }

    /**
     * Wires the full set of stateful mocks used by every scenario below:
     * invoice/history/snapshot/tax-calculation repositories all behave like
     * real repositories across the whole orchestrated flow (in-place
     * mutation for the snapshot/invoice, insert/delete tracking for
     * history/tax-calculation) rather than returning a single canned value.
     */
    private Invoice wireStatefulMocks(BillingSnapshot snapshot, TaxCalculation initialTaxCalculation) {
        Invoice invoice = rejectedInvoice();

        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        savedHistory = new ArrayList<>(List.of(
                historyEntry(InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                        InvoiceApprovalAction.REJECTED, LocalDateTime.of(2026, 9, 1, 10, 0))
        ));
        when(invoiceApprovalHistoryRepository.save(any(InvoiceApprovalHistory.class)))
                .thenAnswer(inv -> {
                    InvoiceApprovalHistory saved = inv.getArgument(0);
                    savedHistory.add(saved);
                    return saved;
                });
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenAnswer(inv -> new ArrayList<>(savedHistory));

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<TaxCalculation> currentTaxCalculation = new AtomicReference<>(initialTaxCalculation);
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId))
                .thenAnswer(inv -> Optional.ofNullable(currentTaxCalculation.get()));
        when(taxCalculationRepository.existsByBillingSnapshotId(snapshotId))
                .thenAnswer(inv -> currentTaxCalculation.get() != null);
        doAnswer(inv -> {
            currentTaxCalculation.set(null);
            return null;
        }).when(taxCalculationRepository).delete(any(TaxCalculation.class));
        when(taxCalculationRepository.save(any(TaxCalculation.class)))
                .thenAnswer(inv -> {
                    TaxCalculation saved = inv.getArgument(0);
                    currentTaxCalculation.set(saved);
                    return saved;
                });

        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(configId))
                .thenReturn(approvedTimeAndMaterialConfiguration());
        // refreshInvoiceFromAuthoritativeData copies the project dates from the configuration.
        when(billingConfigurationService.getBillingConfiguration(configId))
                .thenReturn(com.AccountReceivableManagement.dto.projectbilling_config.BillingConfigurationResponseDto
                        .builder().projectStartDate(LocalDate.of(2026, 8, 1))
                        .projectEndDate(LocalDate.of(2026, 12, 31)).build());
        // TaxCalculationServiceImpl reads the configuration through the
        // repository (not BillingConfigurationService) for its response mapping.
        when(billingConfigurationRepository.findById(configId))
                .thenReturn(Optional.of(epic1ConfigurationForTaxResponseMapping()));
        when(taxConfigurationRepository.findApplicableConfigurations(taxRegionId, PERIOD_START))
                .thenReturn(List.of(taxConfigurationCgstSgst()));

        return invoice;
    }

    private InvoiceApprovalHistory historyEntry(
            InvoiceStatus previousStatus,
            InvoiceStatus newStatus,
            InvoiceApprovalAction action,
            LocalDateTime actionAt
    ) {
        return InvoiceApprovalHistory.builder()
                .invoiceApprovalHistoryId(UUID.randomUUID())
                .invoiceId(invoiceId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .action(action)
                .actionBy("SYSTEM")
                .actionAt(actionAt)
                .build();
    }

    // -----------------------------------------------------------------
    // A/B/C — Only REJECTED invoices can undergo financial correction.
    // -----------------------------------------------------------------

    @Test
    void reacquireForFinancialCorrection_generatedInvoice_throwsValidationException() {
        assertStatusRejected(InvoiceStatus.GENERATED);
    }

    @Test
    void reacquireForFinancialCorrection_pendingApprovalInvoice_throwsValidationException() {
        assertStatusRejected(InvoiceStatus.PENDING_APPROVAL);
    }

    @Test
    void reacquireForFinancialCorrection_approvedInvoice_throwsValidationException() {
        assertStatusRejected(InvoiceStatus.APPROVED);
    }

    private void assertStatusRejected(InvoiceStatus currentStatus) {
        Invoice invoice = rejectedInvoice();
        invoice.setStatus(currentStatus);
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> financialCorrectionService.reacquireForFinancialCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only rejected invoices can undergo financial correction.");

        verify(billingSnapshotRepository, never()).save(any());
        verify(taxCalculationRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractionsWithSourceSystems();
    }

    // -----------------------------------------------------------------
    // D — REJECTED but already corrected since the latest rejection is
    // blocked, mirroring submitForApproval's/correctNonFinancialFields'
    // same cycle-aware guard.
    // -----------------------------------------------------------------

    @Test
    void reacquireForFinancialCorrection_alreadyCorrectedSinceLatestRejection_throwsValidationException() {
        Invoice invoice = rejectedInvoice();
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, LocalDateTime.of(2026, 9, 1, 10, 0)),
                        historyEntry(InvoiceStatus.REJECTED, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.CORRECTED, LocalDateTime.of(2026, 9, 1, 11, 0))
                ));

        assertThatThrownBy(() -> financialCorrectionService.reacquireForFinancialCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice must have a pending correction before financial recalculation.");

        verify(billingSnapshotRepository, never()).save(any());
        verify(taxCalculationRepository, never()).save(any());
        verifyNoInteractionsWithSourceSystems();
    }

    private void verifyNoInteractionsWithSourceSystems() {
        verifyNoInteractions(timesheetIntegration);
        verifyNoInteractions(billingConfigurationIntegration);
    }

    // -----------------------------------------------------------------
    // Invoice not found.
    // -----------------------------------------------------------------

    @Test
    void reacquireForFinancialCorrection_invoiceNotFound_throwsResourceNotFoundException() {
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> financialCorrectionService.reacquireForFinancialCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");
    }

    // -----------------------------------------------------------------
    // E/F/G/H/I/J/K/L/M/N/O/P/Q/R/S/T/U/V/W/X — the full happy path.
    // -----------------------------------------------------------------

    @Test
    void reacquireForFinancialCorrection_rejectedInvoiceWithCorrectedTmsData_rebuildsSnapshotRecalculatesTaxAndRefreshesInvoice() {
        BillingSnapshot snapshot = invoicedSnapshotWithStaleItem();
        TaxCalculation oldTaxCalculation = oldTaxCalculation();
        wireStatefulMocks(snapshot, oldTaxCalculation);

        // Corrected TMS data: 11 hours -> 8 hours (audit's own worked example).
        when(timesheetIntegration.getApprovedTimesheets(PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(correctedTimesheet(new BigDecimal("8"))));

        BillingTMRateCard rateCard = BillingTMRateCard.builder()
                .rateCardId(UUID.randomUUID())
                .rate(new BigDecimal("800"))
                .isActive(true)
                .build();
        when(billingTMRateCardRepository.findActiveRatesByConfigurationAndDate(configId, LocalDate.of(2026, 8, 10)))
                .thenReturn(List.of(rateCard));

        InvoiceResponseDto response = financialCorrectionService.reacquireForFinancialCorrection(invoiceId);

        // F — TMS was actually invoked again for this correction.
        verify(timesheetIntegration).getApprovedTimesheets(PROJECT_ID, PERIOD_START, PERIOD_END);

        // K/L — same BillingSnapshot row, never a new one.
        verify(billingSnapshotRepository, never()).save(argThatNot(snapshot));
        assertThat(snapshot.getId()).isEqualTo(snapshotId);

        // G/H/I — corrected hours/rate/amount reflected in the rebuilt item,
        // via the same rate-card resolution TimeAndMaterialBillingStrategy
        // already uses.
        assertThat(snapshot.getItems()).hasSize(1);
        assertThat(snapshot.getItems().get(0).getQuantity()).isEqualByComparingTo("8");
        assertThat(snapshot.getItems().get(0).getRate()).isEqualByComparingTo("800");
        assertThat(snapshot.getItems().get(0).getAmount()).isEqualByComparingTo("6400");

        // J — snapshot total recalculated from the corrected items.
        assertThat(snapshot.getSubtotal()).isEqualByComparingTo("6400");
        assertThat(snapshot.getTotalAmount()).isEqualByComparingTo("6400");

        // Q — snapshot transitions INVOICED -> READY_FOR_TAX (this method) ->
        // TAX_COMPLETED (the existing, unmodified calculateTax()) -> INVOICED
        // (refreshAfterCorrection(), restoring the lifecycle now that the
        // corrected invoice has been refreshed and persisted).
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);

        // M/N/O/P — old TaxCalculation replaced, new one reflects corrected
        // amounts, no stale components remain.
        verify(taxCalculationRepository).delete(oldTaxCalculation);
        assertThat(response.getTaxCalculationId()).isNotEqualTo(oldTaxCalculationId);
        assertThat(response.getSubtotal()).isEqualByComparingTo("6400.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("1152.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("7552.00");
        assertThat(response.getTaxComponents()).hasSize(2);
        assertThat(response.getTaxComponents())
                .anySatisfy(c -> {
                    assertThat(c.getTaxTypeCode()).isEqualTo("CGST");
                    assertThat(c.getTaxAmount()).isEqualByComparingTo("576.00");
                })
                .anySatisfy(c -> {
                    assertThat(c.getTaxTypeCode()).isEqualTo("SGST");
                    assertThat(c.getTaxAmount()).isEqualByComparingTo("576.00");
                });

        // R — invoice stays REJECTED; not auto-resubmitted.
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        // T — invoice financial values now reflect the corrected source data.
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualByComparingTo("8");
        assertThat(response.getItems().get(0).getAmount()).isEqualByComparingTo("6400");

        // U/V — invoice identity preserved.
        assertThat(response.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260901000000");

        // S/W — refreshAfterCorrection() was actually invoked: exactly one
        // CORRECTED history entry added, the earlier REJECTED entry preserved.
        assertThat(savedHistory).hasSize(2);
        assertThat(savedHistory.get(0).getAction()).isEqualTo(InvoiceApprovalAction.REJECTED);
        InvoiceApprovalHistory correctedEntry = savedHistory.get(1);
        assertThat(correctedEntry.getAction()).isEqualTo(InvoiceApprovalAction.CORRECTED);
        assertThat(correctedEntry.getPreviousStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(correctedEntry.getNewStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(correctedEntry.getComment()).isEqualTo("Invoice refreshed from corrected billing/tax data.");

        // X — correctionRequired becomes false.
        assertThat(response.isCorrectionRequired()).isFalse();
        assertThat(response.getLastCorrectedAt()).isNotNull();
    }


    // -----------------------------------------------------------------
    // End to end: rate schedule -> acquisition -> snapshot -> tax -> invoice, with real service logic and
    // only persistence/integration boundaries mocked. Excluded work must be auditable but never payable.
    // -----------------------------------------------------------------

    private static com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateDiscount periodOn(
            String name, String percent, LocalDate day, boolean include) {
        return com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateDiscount.builder()
                .discountId(UUID.randomUUID()).periodName(name)
                .discountType(com.AccountReceivableManagement.entity_enums.projectbilling_config.DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal(percent)).effectiveFrom(day).effectiveTo(day)
                .includeInInvoice(include).isActive(true).build();
    }

    @Test
    void reacquireForFinancialCorrection_discountSchedule_snapshotTaxAndInvoiceReconcile() {
        BillingSnapshot snapshot = invoicedSnapshotWithStaleItem();
        TaxCalculation oldTaxCalculation = oldTaxCalculation();
        wireStatefulMocks(snapshot, oldTaxCalculation);

        BillingTMRateCard rateCard = BillingTMRateCard.builder().rateCardId(UUID.randomUUID())
                .rate(new BigDecimal("800"))
                .ratePeriod(com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod.HOURLY)
                .includeBaseRateInInvoice(true).isActive(true).build();
        when(billingTMRateCardRepository.findActiveRatesByConfigurationAndDate(eq(configId), any()))
                .thenReturn(List.of(rateCard));

        // 08-10: 25% period, included -> 600/h.  08-11: 50% period, EXCLUDED -> 400/h (audit only).
        // 08-20: no period -> base 800/h, included.
        var launch = periodOn("Launch", "25", LocalDate.of(2026, 8, 10), true);
        var goodwill = periodOn("Goodwill", "50", LocalDate.of(2026, 8, 11), false);
        when(billingTMRateDiscountRepository.findActiveByRateCardAndDate(eq(rateCard.getRateCardId()), any()))
                .thenAnswer(inv -> {
                    LocalDate day = inv.getArgument(1);
                    return day.equals(LocalDate.of(2026, 8, 10)) ? List.of(launch)
                            : day.equals(LocalDate.of(2026, 8, 11)) ? List.of(goodwill) : List.of();
                });

        TimesheetDto d1 = correctedTimesheet(new BigDecimal("8"));
        TimesheetDto d2 = correctedTimesheet(new BigDecimal("8"));
        d2.setSourceReferenceId("TMS-002");
        d2.setWorkDate(LocalDate.of(2026, 8, 11));
        TimesheetDto d3 = correctedTimesheet(new BigDecimal("8"));
        d3.setSourceReferenceId("TMS-003");
        d3.setWorkDate(LocalDate.of(2026, 8, 20));
        when(timesheetIntegration.getApprovedTimesheets(PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(d1, d2, d3));

        InvoiceResponseDto response = financialCorrectionService.reacquireForFinancialCorrection(invoiceId);

        // Snapshot: all three lines kept with their calculation; only two are payable.
        assertThat(snapshot.getItems()).hasSize(3);
        var s1 = byRef(snapshot, "TMS-001");
        var s2 = byRef(snapshot, "TMS-002");
        var s3 = byRef(snapshot, "TMS-003");
        assertThat(s1.getBaseRate()).isEqualByComparingTo("800");
        assertThat(s1.getDiscountAmount()).isEqualByComparingTo("200.00");
        assertThat(s1.getRate()).isEqualByComparingTo("600.00");
        assertThat(s1.getAmount()).isEqualByComparingTo("4800.00");
        assertThat(s1.getDiscountPeriodName()).isEqualTo("Launch");
        assertThat(s1.getIncludeInInvoice()).isTrue();
        assertThat(s2.getAmount()).isEqualByComparingTo("3200.00");   // kept for audit
        assertThat(s2.getIncludeInInvoice()).isFalse();
        assertThat(s3.getRate()).isEqualByComparingTo("800.00");
        assertThat(s3.getDiscountPeriodName()).isNull();
        assertThat(s3.getIncludeInInvoice()).isTrue();

        // Snapshot totals = payable lines only (4800 + 6400), reconciling with the stored line amounts.
        assertThat(snapshot.getSubtotal()).isEqualByComparingTo("11200.00");
        assertThat(snapshot.getTotalAmount()).isEqualByComparingTo("11200.00");
        assertThat(snapshot.getItems().stream().filter(i -> !Boolean.FALSE.equals(i.getIncludeInInvoice()))
                .map(com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(snapshot.getTotalAmount());

        // Tax is calculated on the payable total only (18% = 9% + 9%).
        assertThat(response.getSubtotal()).isEqualByComparingTo("11200.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("2016.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("13216.00");
        assertThat(response.getTaxComponents()).allSatisfy(c ->
                assertThat(c.getTaxAmount()).isEqualByComparingTo("1008.00"));

        // Invoice: the excluded line is not an item; items sum to the invoice subtotal.
        assertThat(response.getItems()).extracting("sourceReferenceId")
                .containsExactlyInAnyOrder("TMS-001", "TMS-003");
        assertThat(response.getItems().stream()
                .map(com.AccountReceivableManagement.dto.invoice_generation.InvoiceItemResponseDto::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(response.getSubtotal());
    }

    private com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem byRef(
            BillingSnapshot snapshot, String ref) {
        return snapshot.getItems().stream().filter(i -> ref.equals(i.getSourceReferenceId()))
                .findFirst().orElseThrow();
    }

    private BillingSnapshot argThatNot(BillingSnapshot expected) {
        return argThat(s -> s != expected);
    }

    // -----------------------------------------------------------------
    // Rule 6 — acquisition validation failure leaves the snapshot and
    // TaxCalculation completely untouched; the Invoice is never reached.
    // -----------------------------------------------------------------

    @Test
    void reacquireForFinancialCorrection_acquisitionValidationFails_leavesSnapshotAndTaxCalculationUntouched() {
        BillingSnapshot snapshot = invoicedSnapshotWithStaleItem();
        TaxCalculation oldTaxCalculation = oldTaxCalculation();
        wireStatefulMocks(snapshot, oldTaxCalculation);

        // TMS now returns nothing for this period (e.g. approvals withdrawn) -
        // validation must fail before anything is mutated.
        when(timesheetIntegration.getApprovedTimesheets(PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of());

        assertThatThrownBy(() -> financialCorrectionService.reacquireForFinancialCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("No timesheets were acquired for the requested billing period.");

        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        assertThat(snapshot.getItems()).hasSize(1);
        assertThat(snapshot.getItems().get(0).getQuantity()).isEqualByComparingTo("11");
        verify(billingSnapshotRepository, never()).save(any());
        verify(taxCalculationRepository, never()).delete(any());
        verify(taxCalculationRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
        // The correctionRequired precondition check itself reads history
        // (expected), but no new CORRECTED entry is ever recorded.
        verify(invoiceApprovalHistoryRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Y — resubmission works after a financial correction, exactly like
    // after a Phase 2B refresh or a Phase 2C non-financial correction.
    // -----------------------------------------------------------------

    @Test
    void reacquireForFinancialCorrection_rejectedInvoice_canBeResubmittedAfterCorrection() {
        BillingSnapshot snapshot = invoicedSnapshotWithStaleItem();
        TaxCalculation oldTaxCalculation = oldTaxCalculation();
        Invoice invoice = wireStatefulMocks(snapshot, oldTaxCalculation);

        when(timesheetIntegration.getApprovedTimesheets(PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(correctedTimesheet(new BigDecimal("8"))));
        when(billingTMRateCardRepository.findActiveRatesByConfigurationAndDate(any(), any()))
                .thenReturn(List.of(BillingTMRateCard.builder().rate(new BigDecimal("800")).isActive(true).build()));

        financialCorrectionService.reacquireForFinancialCorrection(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        InvoiceResponseDto resubmitted = invoiceService.submitForApproval(invoiceId);

        assertThat(resubmitted.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(resubmitted.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(resubmitted.getInvoiceNumber()).isEqualTo("INV-20260901000000");
        assertThat(resubmitted.getGrandTotal()).isEqualByComparingTo("7552.00");

        assertThat(savedHistory).extracting(InvoiceApprovalHistory::getAction)
                .containsExactly(
                        InvoiceApprovalAction.REJECTED,
                        InvoiceApprovalAction.CORRECTED,
                        InvoiceApprovalAction.SUBMITTED
                );

        // The BillingSnapshot must not be stranded at TAX_COMPLETED while this
        // now-resubmitted, active invoice exists against it (the production
        // bug this flow was fixed for).
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
    }
}
