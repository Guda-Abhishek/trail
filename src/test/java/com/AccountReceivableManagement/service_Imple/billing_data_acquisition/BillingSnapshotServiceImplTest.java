package com.AccountReceivableManagement.service_Imple.billing_data_acquisition;

import com.AccountReceivableManagement.builder.billing_data_acquisition.BillingSnapshotBuilder;
import com.AccountReceivableManagement.dependency.billing_data_acquisition.ProjectMasterDataService;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingAcquisitionResultDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotCreateRequestDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.TimesheetDto;
import com.AccountReceivableManagement.dto.common.ApiResponse;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxRegionMaster;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingType;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.integration.billing_data_acquisition.BillingConfigurationIntegration;
import com.AccountReceivableManagement.mapper.billing_data_acquisition.BillingSnapshotMapper;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotRepository;
import com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.CurrencyMasterRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.TaxRegionMasterRepository;
import com.AccountReceivableManagement.service_interface.billing_data_acquisition.BillingAcquisitionService;
import com.AccountReceivableManagement.dto.billing_data_acquisition.AcquireDataResponseDto;
import com.AccountReceivableManagement.strategy.billing_data_acquisition.BillingAcquisitionStrategy;
import com.AccountReceivableManagement.validator.billing_data_acquisition.BillingAcquisitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Note: {@link #billingSnapshotMapper} is deliberately a REAL instance, not a
 * mock. It's the exact object whose {@code snapshot.getItems()} access threw
 * {@code LazyInitializationException} in production once the Hibernate
 * session that loaded the snapshot had closed (see
 * {@link BillingSnapshotRepository#findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd}
 * for the fix — an {@code @EntityGraph} that eagerly fetches {@code items}).
 * Running the real mapper here exercises that exact code path end to end.
 */
@ExtendWith(MockitoExtension.class)
class BillingSnapshotServiceImplTest {

    @Mock
    private BillingSnapshotRepository billingSnapshotRepository;

    @Mock
    private BillingConfigurationIntegration billingConfigurationIntegration;

    @Mock
    private ProjectMasterDataService projectMasterDataService;

    @Mock
    private CurrencyMasterRepository currencyMasterRepository;

    @Mock
    private ProjectMasterReferenceRepository projectMasterReferenceRepository;

    @Mock
    private TaxRegionMasterRepository taxRegionMasterRepository;

    @Mock
    private BillingAcquisitionStrategy timeAndMaterialStrategy;

    @Mock
    private BillingAcquisitionService billingAcquisitionService;

    @Mock
    private PlatformTransactionManager transactionManager;

    // Real, dependency-free collaborators — using the actual production
    // logic here (not mocks) is what lets these tests prove the acquisition
    // pipeline genuinely attaches/maps source timesheet items, not just
    // that some mock was told to return them.
    private final BillingAcquisitionValidator billingAcquisitionValidator = new BillingAcquisitionValidator();
    private final BillingSnapshotBuilder billingSnapshotBuilder = new BillingSnapshotBuilder();
    private final BillingSnapshotMapper billingSnapshotMapper = new BillingSnapshotMapper();

    private BillingSnapshotServiceImpl service;

    private static final Long PROJECT_ID = 23L;
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 5);
    private static final LocalDate PERIOD_END = LocalDate.of(2027, 1, 8);

    private UUID snapshotId;
    private UUID orphanedConfigurationId;
    private BillingSnapshot existingSnapshot;

    @BeforeEach
    void setUp() {
        when(timeAndMaterialStrategy.getSupportedBillingType()).thenReturn(BillingType.TIME_AND_MATERIAL);

        service = new BillingSnapshotServiceImpl(
                billingSnapshotRepository,
                billingConfigurationIntegration,
                projectMasterDataService,
                billingAcquisitionValidator,
                billingSnapshotBuilder,
                billingSnapshotMapper,
                currencyMasterRepository,
                projectMasterReferenceRepository,
                taxRegionMasterRepository,
                List.of(timeAndMaterialStrategy),
                billingAcquisitionService,
                transactionManager);

        snapshotId = UUID.randomUUID();
        orphanedConfigurationId = UUID.randomUUID();

        BillingSnapshotItem item = BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID())
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("Jane Doe")
                .sourceReferenceId("TMS-001")
                .quantity(BigDecimal.valueOf(40))
                .rate(BigDecimal.valueOf(250))
                .amount(BigDecimal.valueOf(10000))
                .workDate(LocalDate.of(2026, 8, 10))
                .approvalStatus("APPROVED")
                .role("Developer")
                .build();

        existingSnapshot = BillingSnapshot.builder()
                .id(snapshotId)
                .snapshotNumber("BS-20260805120000")
                .billingConfigurationId(orphanedConfigurationId)
                .clientId(UUID.randomUUID())
                .projectId(PROJECT_ID)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .status(BillingSnapshotStatus.READY_FOR_TAX)
                .subtotal(BigDecimal.valueOf(10000))
                .expenseAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.valueOf(10000))
                .items(List.of(item))
                .build();
    }

    // =========================================================
    // A. getByProjectAndPeriod — historical configuration missing
    // =========================================================

    @Test
    void getByProjectAndPeriod_historicalConfigurationMissing_returnsSnapshotWithEnrichmentOmitted() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(existingSnapshot));

        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenThrow(new GlobalExceptionHandler.ResourceNotFoundException(
                        "Billing Configuration not found with id: " + orphanedConfigurationId));

        // No exception must escape this call, and the real mapper must be
        // able to read snapshot.getItems() without a LazyInitializationException.
        ApiResponse<BillingSnapshotResponseDto> response =
                service.getByProjectAndPeriod(PROJECT_ID, PERIOD_START, PERIOD_END);

        assertThat(response.isSuccess()).isTrue();
        BillingSnapshotResponseDto data = response.getData();
        assertThat(data.getSnapshotId()).isEqualTo(snapshotId);
        assertThat(data.getBillingPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(data.getBillingPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(data.getStatus()).isEqualTo(BillingSnapshotStatus.READY_FOR_TAX);
        assertThat(data.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));

        // Items were mapped successfully.
        assertThat(data.getTimesheets()).hasSize(1);
        assertThat(data.getTimesheets().get(0).getEmployee()).isEqualTo("Jane Doe");
        assertThat(data.getTimesheets().get(0).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(10000));

        // The historical reference itself must never be rewritten.
        assertThat(existingSnapshot.getBillingConfigurationId()).isEqualTo(orphanedConfigurationId);

        // Only the configuration-derived enrichment fields are affected.
        assertThat(data.getBillingTypeName()).isNull();
        assertThat(data.getCurrencyCode()).isNull();
        assertThat(data.getPaymentTermName()).isNull();
        assertThat(data.getBillingFrequencyName()).isNull();
        assertThat(data.getTaxRegionCode()).isNull();

        verify(billingSnapshotRepository, never()).save(any());
    }

    // =========================================================
    // B. createBillingSnapshot — existing snapshot branch, historical
    //    configuration missing
    // =========================================================

    @Test
    void createBillingSnapshot_existingSnapshot_historicalConfigurationMissing_returnsSnapshotSuccessfully() {
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .build();

        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(existingSnapshot));

        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenThrow(new GlobalExceptionHandler.ResourceNotFoundException(
                        "Billing Configuration not found with id: " + orphanedConfigurationId));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getData().getTimesheets()).hasSize(1);
        assertThat(existingSnapshot.getBillingConfigurationId()).isEqualTo(orphanedConfigurationId);

        // A pre-existing snapshot must never be re-acquired/re-persisted —
        // clicking "View" must not create a new BillingSnapshot.
        verify(billingSnapshotRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).saveAndFlush(any());
    }

    // =========================================================
    // C. Happy path — historical configuration still resolvable
    // =========================================================

    @Test
    void getByProjectAndPeriod_configurationFound_returnsEnrichedSnapshot() {
        UUID activeConfigurationId = UUID.randomUUID();
        existingSnapshot.setBillingConfigurationId(activeConfigurationId);

        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(existingSnapshot));

        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(activeConfigurationId)
                .billingTypeName("Timesheet Based")
                .currencyCode("USD")
                .build();

        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(activeConfigurationId))
                .thenReturn(configuration);

        ApiResponse<BillingSnapshotResponseDto> response =
                service.getByProjectAndPeriod(PROJECT_ID, PERIOD_START, PERIOD_END);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getData().getBillingTypeName()).isEqualTo("Timesheet Based");
        assertThat(response.getData().getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getData().getTimesheets()).hasSize(1);
    }

    @Test
    void getByProjectAndPeriod_noSnapshot_returnsFailureWithoutThrowing() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.empty());

        ApiResponse<BillingSnapshotResponseDto> response =
                service.getByProjectAndPeriod(PROJECT_ID, PERIOD_START, PERIOD_END);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage())
                .isEqualTo("Billing Snapshot not found for the selected project and billing period.");
    }

    // =========================================================
    // D. Unexpected exceptions must still propagate
    // =========================================================

    @Test
    void getByProjectAndPeriod_unexpectedExceptionFromIntegration_propagates() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(existingSnapshot));

        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenThrow(new IllegalStateException("Database connection lost"));

        assertThatThrownBy(() -> service.getByProjectAndPeriod(PROJECT_ID, PERIOD_START, PERIOD_END))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database connection lost");
    }

    // =========================================================
    // E. First-time acquisition persists the source timesheet items
    // =========================================================

    @Test
    void createBillingSnapshot_newSnapshot_persistsSourceTimesheetItems() {
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .build();

        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.empty());

        UUID configurationId = UUID.randomUUID();
        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(configurationId)
                .billingType(BillingType.TIME_AND_MATERIAL)
                .billingTypeName("Timesheet Based")
                .currencyId(UUID.randomUUID())
                .currencyCode("USD")
                .taxRegionCode("DOM")
                .approved(true)
                .build();
        when(billingConfigurationIntegration.getApprovedBillingConfiguration(PROJECT_ID))
                .thenReturn(configuration);

        when(projectMasterDataService.getClientIdByProjectId(PROJECT_ID)).thenReturn(UUID.randomUUID());

        ProjectMasterReference project = ProjectMasterReference.builder()
                .pmsProjectId(PROJECT_ID)
                .primaryLocation("Domestic")
                .build();
        when(projectMasterReferenceRepository.findBypmsProjectId(PROJECT_ID))
                .thenReturn(Optional.of(project));

        TaxRegionMaster taxRegion = TaxRegionMaster.builder()
                .taxRegionCode("DOM")
                .build();
        when(taxRegionMasterRepository.findByTaxRegionNameIgnoreCase("Domestic"))
                .thenReturn(Optional.of(taxRegion));

        TimesheetDto timesheet = TimesheetDto.builder()
                .resourceId(1L)
                .resourceName("Jane Doe")
                .sourceReferenceId("TMS-100")
                .workDate(LocalDate.of(2026, 8, 10))
                .hours(BigDecimal.valueOf(8))
                .hourlyRate(BigDecimal.valueOf(165))
                .role("Developer")
                .approvalStatus("APPROVED")
                .approved(true)
                .billable(true)
                .build();
        when(timeAndMaterialStrategy.acquire(configuration, request)).thenReturn(
                BillingAcquisitionResultDto.builder()
                        .billingConfigurationId(configurationId)
                        .timesheets(List.of(timesheet))
                        .build());

        ArgumentCaptor<BillingSnapshot> savedCaptor = ArgumentCaptor.forClass(BillingSnapshot.class);
        when(billingSnapshotRepository.saveAndFlush(savedCaptor.capture()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();

        // The entity actually handed to the repository for persistence must
        // already carry its source timesheet as a BillingSnapshotItem —
        // this is the exact object graph Hibernate cascades into
        // billing_snapshot_item on save.
        BillingSnapshot persisted = savedCaptor.getValue();
        assertThat(persisted.getItems()).hasSize(1);
        assertThat(persisted.getItems().get(0).getItemName()).isEqualTo("Jane Doe");
        assertThat(persisted.getItems().get(0).getSourceReferenceId()).isEqualTo("TMS-100");
        assertThat(persisted.getItems().get(0).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1320));
        assertThat(persisted.getItems().get(0).getBillingSnapshot()).isSameAs(persisted);

        // ...and the response the frontend receives reflects the same data.
        assertThat(response.getData().getTimesheets()).hasSize(1);
        assertThat(response.getData().getTimesheets().get(0).getEmployee()).isEqualTo("Jane Doe");
        assertThat(response.getData().getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(1320));
    }

    // XMS expenses are an additional source: costBasis is summed as-is into
    // expenseAmount/totalAmount (the amount tax calculation runs on), persisted
    // as EXPENSE snapshot items, and kept out of the Labor Charges list.
    @Test
    void createBillingSnapshot_withXmsExpenses_persistsExpenseItemsAndTotals() {
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID).billingPeriodStart(PERIOD_START).billingPeriodEnd(PERIOD_END).build();

        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());

        UUID configurationId = UUID.randomUUID();
        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(configurationId)
                .billingType(BillingType.TIME_AND_MATERIAL).billingTypeName("Timesheet Based")
                .currencyId(UUID.randomUUID()).currencyCode("USD").taxRegionCode("DOM")
                .expenseEligible(true).approved(true).build();
        when(billingConfigurationIntegration.getApprovedBillingConfiguration(PROJECT_ID)).thenReturn(configuration);
        when(projectMasterDataService.getClientIdByProjectId(PROJECT_ID)).thenReturn(UUID.randomUUID());
        when(projectMasterReferenceRepository.findBypmsProjectId(PROJECT_ID)).thenReturn(Optional.of(
                ProjectMasterReference.builder().pmsProjectId(PROJECT_ID).primaryLocation("Domestic").build()));
        when(taxRegionMasterRepository.findByTaxRegionNameIgnoreCase("Domestic"))
                .thenReturn(Optional.of(TaxRegionMaster.builder().taxRegionCode("DOM").build()));

        TimesheetDto timesheet = TimesheetDto.builder().resourceId(1L).resourceName("Jane Doe")
                .sourceReferenceId("TMS-100").workDate(LocalDate.of(2026, 8, 10))
                .hours(BigDecimal.valueOf(8)).hourlyRate(BigDecimal.valueOf(165)).role("Developer")
                .approvalStatus("APPROVED").approved(true).billable(true).build();
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto expense =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L1").category("Travel").expenseDate(LocalDate.of(2026, 8, 12))
                        .costBasis(new BigDecimal("50.00")).currency("USD")
                        .grossAmount(new BigDecimal("60.00")).recoverableTaxAmount(new BigDecimal("10.00")).build();
        when(timeAndMaterialStrategy.acquire(configuration, request)).thenReturn(
                BillingAcquisitionResultDto.builder().billingConfigurationId(configurationId)
                        .timesheets(List.of(timesheet)).expenses(List.of(expense)).build());

        ArgumentCaptor<BillingSnapshot> savedCaptor = ArgumentCaptor.forClass(BillingSnapshot.class);
        when(billingSnapshotRepository.saveAndFlush(savedCaptor.capture()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        BillingSnapshot persisted = savedCaptor.getValue();
        assertThat(persisted.getSubtotal()).isEqualByComparingTo("1320");
        assertThat(persisted.getExpenseAmount()).isEqualByComparingTo("50.00");
        assertThat(persisted.getTotalAmount()).isEqualByComparingTo("1370.00");
        assertThat(persisted.getItems()).hasSize(2);
        BillingSnapshotItem expenseItem = persisted.getItems().stream()
                .filter(i -> i.getItemType() == BillingItemType.EXPENSE).findFirst().orElseThrow();
        assertThat(expenseItem.getSourceReferenceId()).isEqualTo("XMS-L1");
        assertThat(expenseItem.getAmount()).isEqualByComparingTo("50.00");
        assertThat(expenseItem.getBillingSnapshot()).isSameAs(persisted);

        // Labor Charges list stays labor-only; the total includes expenses.
        assertThat(response.getData().getTimesheets()).hasSize(1);
        assertThat(response.getData().getTotalAmount()).isEqualByComparingTo("1370.00");
    }

    // XMS failing for an expense-eligible project aborts creation: nothing is
    // persisted (no labor-only snapshot silently missing its expenses) and the
    // integration error reaches the caller as-is, not as an empty expense list.
    @Test
    void createBillingSnapshot_xmsFailure_persistsNothingAndSurfacesTheIntegrationError() {
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID).billingPeriodStart(PERIOD_START).billingPeriodEnd(PERIOD_END).build();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(UUID.randomUUID()).billingType(BillingType.TIME_AND_MATERIAL)
                .currencyId(UUID.randomUUID()).currencyCode("USD").taxRegionCode("DOM")
                .expenseEligible(true).approved(true).build();
        when(billingConfigurationIntegration.getApprovedBillingConfiguration(PROJECT_ID)).thenReturn(configuration);
        when(projectMasterDataService.getClientIdByProjectId(PROJECT_ID)).thenReturn(UUID.randomUUID());
        when(projectMasterReferenceRepository.findBypmsProjectId(PROJECT_ID)).thenReturn(Optional.of(
                ProjectMasterReference.builder().pmsProjectId(PROJECT_ID).primaryLocation("Domestic").build()));
        when(taxRegionMasterRepository.findByTaxRegionNameIgnoreCase("Domestic"))
                .thenReturn(Optional.of(TaxRegionMaster.builder().taxRegionCode("DOM").build()));
        when(timeAndMaterialStrategy.acquire(configuration, request)).thenThrow(
                new com.AccountReceivableManagement.integration.expense_management.XmsIntegrationException(
                        com.AccountReceivableManagement.integration.expense_management.XmsIntegrationException.Kind.UNAVAILABLE,
                        500, "XMS call failed with status 500 (eligible-expenses)."));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createBillingSnapshot(request))
                .isInstanceOf(com.AccountReceivableManagement.integration.expense_management.XmsIntegrationException.class)
                .hasMessageContaining("status 500");

        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verify(billingSnapshotRepository, never()).save(any());
    }

    // =========================================================
    // F. Empty items are allowed on retrieval — not an error state
    // =========================================================

    @Test
    void getByProjectAndPeriod_snapshotWithNoItems_returnsEmptyTimesheetsSuccessfully() {
        existingSnapshot.setItems(List.of());

        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.of(existingSnapshot));

        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenThrow(new GlobalExceptionHandler.ResourceNotFoundException("not found"));

        ApiResponse<BillingSnapshotResponseDto> response =
                service.getByProjectAndPeriod(PROJECT_ID, PERIOD_START, PERIOD_END);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getTimesheets()).isNotNull();
        assertThat(response.getData().getTimesheets()).isEmpty();
    }

    // =========================================================
    // G. rebuildBillingSnapshot — Phase 2B financial correction
    // =========================================================

    private BillingSnapshot mutableExistingSnapshot() {
        BillingSnapshotItem staleItem = BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID())
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("Jane Doe")
                .sourceReferenceId("TMS-001")
                .quantity(BigDecimal.valueOf(11))
                .rate(BigDecimal.valueOf(800))
                .amount(BigDecimal.valueOf(8800))
                .workDate(LocalDate.of(2026, 8, 10))
                .approvalStatus("APPROVED")
                .role("Developer")
                .build();

        // A genuinely mutable items list - rebuildBillingSnapshot() clears and
        // repopulates it in place, which List.of(...) (used by the shared
        // existingSnapshot fixture) would reject with UnsupportedOperationException.
        List<BillingSnapshotItem> items = new ArrayList<>(List.of(staleItem));

        return BillingSnapshot.builder()
                .id(snapshotId)
                .snapshotNumber("BS-20260805120000")
                .billingConfigurationId(orphanedConfigurationId)
                .clientId(UUID.randomUUID())
                .projectId(PROJECT_ID)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .status(BillingSnapshotStatus.INVOICED)
                .subtotal(BigDecimal.valueOf(8800))
                .expenseAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.valueOf(8800))
                .items(items)
                .build();
    }

    private BillingConfigurationResponseDto approvedTimeAndMaterialConfiguration(UUID configurationId) {
        return BillingConfigurationResponseDto.builder()
                .billingConfigurationId(configurationId)
                .billingType(BillingType.TIME_AND_MATERIAL)
                .billingTypeName("Timesheet Based")
                .currencyCode("USD")
                .taxRegionCode("DOM")
                .approved(true)
                .build();
    }

    @Test
    void rebuildBillingSnapshot_snapshotNotFound_throwsResourceNotFoundException() {
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rebuildBillingSnapshot(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Billing snapshot could not be found.");

        verify(billingSnapshotRepository, never()).save(any());
    }

    @Test
    void rebuildBillingSnapshot_configurationNotApproved_throwsValidationExceptionAndLeavesSnapshotUntouched() {
        BillingSnapshot snapshot = mutableExistingSnapshot();
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        BillingConfigurationResponseDto notApproved = approvedTimeAndMaterialConfiguration(orphanedConfigurationId);
        notApproved.setApproved(false);
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(notApproved);

        assertThatThrownBy(() -> service.rebuildBillingSnapshot(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Approved Billing Configuration not found.");

        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        assertThat(snapshot.getItems()).hasSize(1);
        verify(billingSnapshotRepository, never()).save(any());
        // The strategy is resolved by billing type but never invoked - the
        // approval check fails before any source-system acquisition begins.
        verify(timeAndMaterialStrategy, never()).acquire(any(), any());
    }

    @Test
    void rebuildBillingSnapshot_unsupportedBillingType_throwsValidationException() {
        BillingSnapshot snapshot = mutableExistingSnapshot();
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        BillingConfigurationResponseDto fixedPriceConfig = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(orphanedConfigurationId)
                .billingType(BillingType.FIXED_PRICE)
                .approved(true)
                .build();
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(fixedPriceConfig);

        assertThatThrownBy(() -> service.rebuildBillingSnapshot(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessageContaining("is not yet supported");

        verify(billingSnapshotRepository, never()).save(any());
    }

    @Test
    void rebuildBillingSnapshot_acquiredDataFailsValidation_leavesSnapshotCompletelyUntouched() {
        BillingSnapshot snapshot = mutableExistingSnapshot();
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        BillingConfigurationResponseDto configuration = approvedTimeAndMaterialConfiguration(orphanedConfigurationId);
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(configuration);

        // Re-acquisition genuinely returns nothing this time (e.g. TMS has no
        // approved timesheets left for the period) - must fail validation,
        // not silently persist an empty/zero snapshot.
        when(timeAndMaterialStrategy.acquire(eq(configuration), any(BillingSnapshotCreateRequestDto.class)))
                .thenReturn(BillingAcquisitionResultDto.builder()
                        .billingConfigurationId(orphanedConfigurationId)
                        .timesheets(List.of())
                        .build());

        assertThatThrownBy(() -> service.rebuildBillingSnapshot(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("No timesheets were acquired for the requested billing period.");

        // Nothing on the pre-existing snapshot was touched by the failed attempt.
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        assertThat(snapshot.getItems()).hasSize(1);
        assertThat(snapshot.getItems().get(0).getQuantity()).isEqualByComparingTo("11");
        assertThat(snapshot.getTotalAmount()).isEqualByComparingTo("8800");
        verify(billingSnapshotRepository, never()).save(any());
    }

    @Test
    void rebuildBillingSnapshot_keepsTheFrozenConversionOfAnExpenseAlreadyConverted() {
        BillingSnapshot snapshot = mutableExistingSnapshot();
        snapshot.getItems().add(BillingSnapshotItem.builder()
                .itemType(BillingItemType.EXPENSE).sourceReferenceId("XMS-L9").amount(new BigDecimal("5.00"))
                .sourceAmount(new BigDecimal("400.0000")).sourceCurrency("INR").targetCurrency("USD")
                .exchangeRate(new BigDecimal("0.01250000")).exchangeRateDate(LocalDate.of(2026, 8, 12))
                .exchangeRateSource("FROZEN-REF").build());
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        BillingConfigurationResponseDto configuration = approvedTimeAndMaterialConfiguration(orphanedConfigurationId);
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(configuration);

        TimesheetDto timesheet = TimesheetDto.builder().resourceId(1L).resourceName("Jane Doe")
                .sourceReferenceId("TMS-001").workDate(LocalDate.of(2026, 8, 10)).hours(BigDecimal.valueOf(8))
                .hourlyRate(BigDecimal.valueOf(800)).role("Developer").approvalStatus("APPROVED")
                .approved(true).billable(true).build();
        // XMS still reports the same source amount, but a re-acquisition now yields a DIFFERENT rate.
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto reconverted =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L9").category("Hotel").expenseDate(LocalDate.of(2026, 8, 12))
                        .costBasis(new BigDecimal("6.00")).currency("USD")
                        .sourceAmount(new BigDecimal("400.0000")).sourceCurrency("INR")
                        .exchangeRate(new BigDecimal("0.01500000")).exchangeRateDate(LocalDate.of(2026, 8, 13))
                        .exchangeRateSource("NEWER-REF").build();
        when(timeAndMaterialStrategy.acquire(eq(configuration), any(BillingSnapshotCreateRequestDto.class)))
                .thenReturn(BillingAcquisitionResultDto.builder().billingConfigurationId(orphanedConfigurationId)
                        .timesheets(List.of(timesheet)).expenses(List.of(reconverted)).build());
        when(billingSnapshotRepository.save(any(BillingSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillingSnapshot result = service.rebuildBillingSnapshot(snapshotId);

        BillingSnapshotItem expense = result.getItems().stream()
                .filter(i -> i.getItemType() == BillingItemType.EXPENSE).findFirst().orElseThrow();
        assertThat(expense.getAmount()).isEqualByComparingTo("5.00");
        assertThat(expense.getExchangeRate()).isEqualByComparingTo("0.0125");
        assertThat(expense.getExchangeRateSource()).isEqualTo("FROZEN-REF");
        assertThat(result.getExpenseAmount()).isEqualByComparingTo("5.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("6405.00");
    }

    @Test
    void createBillingSnapshot_withConvertedExpense_totalsContainOnlyBillingCurrencyAmounts() {
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID).billingPeriodStart(PERIOD_START).billingPeriodEnd(PERIOD_END).build();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        UUID configurationId = UUID.randomUUID();
        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(configurationId)
                .billingType(BillingType.TIME_AND_MATERIAL).billingTypeName("Timesheet Based")
                .currencyId(UUID.randomUUID()).currencyCode("USD").taxRegionCode("DOM")
                .expenseEligible(true).approved(true).build();
        when(billingConfigurationIntegration.getApprovedBillingConfiguration(PROJECT_ID)).thenReturn(configuration);
        when(projectMasterDataService.getClientIdByProjectId(PROJECT_ID)).thenReturn(UUID.randomUUID());
        when(projectMasterReferenceRepository.findBypmsProjectId(PROJECT_ID)).thenReturn(Optional.of(
                ProjectMasterReference.builder().pmsProjectId(PROJECT_ID).primaryLocation("Domestic").build()));
        when(taxRegionMasterRepository.findByTaxRegionNameIgnoreCase("Domestic"))
                .thenReturn(Optional.of(TaxRegionMaster.builder().taxRegionCode("DOM").build()));
        TimesheetDto timesheet = TimesheetDto.builder().resourceId(1L).resourceName("Jane Doe")
                .sourceReferenceId("TMS-100").workDate(LocalDate.of(2026, 8, 10))
                .hours(BigDecimal.valueOf(8)).hourlyRate(BigDecimal.valueOf(165)).role("Developer")
                .approvalStatus("APPROVED").approved(true).billable(true).build();
        // costBasis here is the already-converted USD amount; the INR original is audit-only.
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto converted =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L9").category("Hotel").expenseDate(LocalDate.of(2026, 8, 12))
                        .costBasis(new BigDecimal("5.00")).currency("USD")
                        .sourceAmount(new BigDecimal("400.0000")).sourceCurrency("INR")
                        .exchangeRate(new BigDecimal("0.01250000")).exchangeRateDate(LocalDate.of(2026, 8, 12))
                        .exchangeRateSource("TEST-REF").build();
        when(timeAndMaterialStrategy.acquire(configuration, request)).thenReturn(
                BillingAcquisitionResultDto.builder().billingConfigurationId(configurationId)
                        .timesheets(List.of(timesheet)).expenses(List.of(converted)).build());
        ArgumentCaptor<BillingSnapshot> savedCaptor = ArgumentCaptor.forClass(BillingSnapshot.class);
        when(billingSnapshotRepository.saveAndFlush(savedCaptor.capture()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        BillingSnapshot persisted = savedCaptor.getValue();
        assertThat(persisted.getCurrencyCode()).isEqualTo("USD");
        assertThat(persisted.getExpenseAmount()).isEqualByComparingTo("5.00");   // never 400 (INR)
        assertThat(persisted.getTotalAmount()).isEqualByComparingTo("1325.00");  // 1320 labor + 5 expense
        BillingSnapshotItem item = persisted.getItems().stream()
                .filter(i -> i.getItemType() == BillingItemType.EXPENSE).findFirst().orElseThrow();
        assertThat(item.getAmount()).isEqualByComparingTo("5.00");
        assertThat(item.getSourceAmount()).isEqualByComparingTo("400.0000");
        assertThat(item.getExchangeRateSource()).isEqualTo("TEST-REF");
    }

    @Test
    void rebuildBillingSnapshot_correctedTmsData_replacesItemsRecalculatesTotalsAndSetsReadyForTax() {
        BillingSnapshot snapshot = mutableExistingSnapshot();
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        BillingConfigurationResponseDto configuration = approvedTimeAndMaterialConfiguration(orphanedConfigurationId);
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(configuration);

        // Corrected TMS data: hours 11 -> 8, same rate, same worked example as the
        // Phase 2B audit (Old: 11 x 800 = 8,800. New: 8 x 800 = 6,400.).
        TimesheetDto correctedTimesheet = TimesheetDto.builder()
                .resourceId(1L)
                .resourceName("Jane Doe")
                .sourceReferenceId("TMS-001")
                .workDate(LocalDate.of(2026, 8, 10))
                .hours(BigDecimal.valueOf(8))
                .hourlyRate(BigDecimal.valueOf(800))
                .role("Developer")
                .approvalStatus("APPROVED")
                .approved(true)
                .billable(true)
                .build();
        when(timeAndMaterialStrategy.acquire(eq(configuration), any(BillingSnapshotCreateRequestDto.class)))
                .thenReturn(BillingAcquisitionResultDto.builder()
                        .billingConfigurationId(orphanedConfigurationId)
                        .timesheets(List.of(correctedTimesheet))
                        .build());

        when(billingSnapshotRepository.save(any(BillingSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillingSnapshot result = service.rebuildBillingSnapshot(snapshotId);

        // Same row - never a new BillingSnapshot.
        assertThat(result).isSameAs(snapshot);
        assertThat(result.getId()).isEqualTo(snapshotId);

        // Old item fully replaced by the corrected one.
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getQuantity()).isEqualByComparingTo("8");
        assertThat(result.getItems().get(0).getRate()).isEqualByComparingTo("800");
        assertThat(result.getItems().get(0).getAmount()).isEqualByComparingTo("6400");
        assertThat(result.getItems().get(0).getItemName()).isEqualTo("Jane Doe");
        assertThat(result.getItems().get(0).getBillingSnapshot()).isSameAs(result);
        assertThat(result.getItems()).noneMatch(item -> "TMS-001-stale".equals(item.getSourceReferenceId()));

        // Snapshot totals recalculated from the corrected items.
        assertThat(result.getSubtotal()).isEqualByComparingTo("6400");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("6400");
        assertThat(result.getExpenseAmount()).isEqualByComparingTo("0");

        // Status reset only for the correction workflow - never touched by
        // normal acquisition (createBillingSnapshot never calls this method).
        assertThat(result.getStatus()).isEqualTo(BillingSnapshotStatus.READY_FOR_TAX);

        // Identity and unrelated fields (billing period, currency, tax region,
        // client/project) are never touched by the rebuild.
        assertThat(result.getSnapshotNumber()).isEqualTo("BS-20260805120000");
        assertThat(result.getBillingConfigurationId()).isEqualTo(orphanedConfigurationId);
        assertThat(result.getBillingPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(result.getBillingPeriodEnd()).isEqualTo(PERIOD_END);

        verify(billingSnapshotRepository).save(snapshot);
    }

    // =========================================================
    // H. Snapshot / acquisition-record consistency and idempotency
    // =========================================================

    private static final UUID NEW_CONFIGURATION_ID = UUID.randomUUID();

    private BillingSnapshotCreateRequestDto newRequest() {
        return BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .build();
    }

    private TimesheetDto timesheet(boolean approved) {
        return TimesheetDto.builder()
                .resourceId(1L)
                .resourceName("Jane Doe")
                .sourceReferenceId("TMS-100")
                .workDate(LocalDate.of(2026, 8, 10))
                .hours(BigDecimal.valueOf(8))
                .hourlyRate(BigDecimal.valueOf(165))
                .role("Developer")
                .approvalStatus(approved ? "APPROVED" : "SUBMITTED")
                .approved(approved)
                .billable(true)
                .build();
    }

    /** Stubs everything createBillingSnapshot needs up to (not including) the strategy call. */
    private BillingConfigurationResponseDto stubConfigurationAndJurisdictions() {
        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingConfigurationId(NEW_CONFIGURATION_ID)
                .billingType(BillingType.TIME_AND_MATERIAL)
                .billingTypeName("Timesheet Based")
                .currencyId(UUID.randomUUID())
                .currencyCode("USD")
                .taxRegionCode("DOM")
                .approved(true)
                .build();
        when(billingConfigurationIntegration.getApprovedBillingConfiguration(PROJECT_ID)).thenReturn(configuration);
        when(projectMasterDataService.getClientIdByProjectId(PROJECT_ID)).thenReturn(UUID.randomUUID());
        when(projectMasterReferenceRepository.findBypmsProjectId(PROJECT_ID)).thenReturn(Optional.of(
                ProjectMasterReference.builder().pmsProjectId(PROJECT_ID).primaryLocation("Domestic").build()));
        when(taxRegionMasterRepository.findByTaxRegionNameIgnoreCase("Domestic"))
                .thenReturn(Optional.of(TaxRegionMaster.builder().taxRegionCode("DOM").build()));
        return configuration;
    }

    private void stubAcquiredTimesheets(BillingConfigurationResponseDto configuration,
            BillingSnapshotCreateRequestDto request, TimesheetDto timesheet) {
        when(timeAndMaterialStrategy.acquire(configuration, request)).thenReturn(
                BillingAcquisitionResultDto.builder()
                        .billingConfigurationId(NEW_CONFIGURATION_ID)
                        .timesheets(List.of(timesheet))
                        .build());
    }

    private AcquireDataResponseDto acquisition(UUID snapshotIdRef, String status) {
        return AcquireDataResponseDto.builder()
                .id(UUID.randomUUID())
                .projectId(PROJECT_ID)
                .snapshotId(snapshotIdRef)
                .status(status)
                .build();
    }

    // Test 1 - new successful acquisition
    @Test
    void createBillingSnapshot_newSnapshot_recordsAcquisitionForSavedSnapshotInSameTransaction() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        stubAcquiredTimesheets(configuration, request, timesheet(true));

        UUID savedId = UUID.randomUUID();
        ArgumentCaptor<BillingSnapshot> savedCaptor = ArgumentCaptor.forClass(BillingSnapshot.class);
        when(billingSnapshotRepository.saveAndFlush(savedCaptor.capture())).thenAnswer(invocation -> {
            BillingSnapshot toSave = invocation.getArgument(0);
            toSave.setId(savedId);
            return toSave;
        });
        when(billingAcquisitionService.createManualAcquisition(
                NEW_CONFIGURATION_ID, PERIOD_START, PERIOD_END, savedId, "READY"))
                .thenReturn(acquisition(savedId, "READY"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(savedId);
        assertThat(response.getData().getExistingSnapshot()).isFalse();
        assertThat(response.getData().getAcquisitionStatus()).isEqualTo("READY");
        assertThat(response.getData().getStatus()).isEqualTo(BillingSnapshotStatus.READY_FOR_TAX);

        BillingSnapshot persisted = savedCaptor.getValue();
        assertThat(persisted.getItems()).hasSize(1);
        assertThat(persisted.getBillingConfigurationId()).isEqualTo(NEW_CONFIGURATION_ID);

        // Acquisition record references the saved snapshot, written in the
        // same (single, committed) transaction as the snapshot.
        verify(billingAcquisitionService).createManualAcquisition(
                NEW_CONFIGURATION_ID, PERIOD_START, PERIOD_END, savedId, "READY");
        verify(transactionManager, times(1)).getTransaction(any());
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void createBillingSnapshot_conversionFailsDuringAcquisition_nothingIsPersistedAndNoTransactionIsStarted() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        stubConfigurationAndJurisdictions();
        // Labor was acquired fine; an expense then fails currency conversion inside the same acquisition.
        when(timeAndMaterialStrategy.acquire(any(), eq(request))).thenThrow(new GlobalExceptionHandler.ValidationException(
                "XMS expense [L9] rejected: Expense currency conversion failed: no approved exchange rate is available "
                        + "for INR to USD on 2026-08-12"));

        assertThatThrownBy(() -> service.createBillingSnapshot(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessageContaining("currency conversion failed");

        // Acquisition runs before the persistence transaction: the failure leaves no snapshot behind.
        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verify(billingAcquisitionService, never()).createManualAcquisition(any(), any(), any(), any(), any());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void createBillingSnapshot_persistenceFailsForAMixedConvertedSnapshot_rollsBackAndReportsFailure() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto converted =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L9").category("Hotel").expenseDate(LocalDate.of(2026, 8, 12))
                        .costBasis(new BigDecimal("5.00")).currency("USD")
                        .sourceAmount(new BigDecimal("400.0000")).sourceCurrency("INR")
                        .exchangeRate(new BigDecimal("0.01250000")).exchangeRateDate(LocalDate.of(2026, 8, 12))
                        .exchangeRateSource("TEST-REF").build();
        when(timeAndMaterialStrategy.acquire(configuration, request)).thenReturn(
                BillingAcquisitionResultDto.builder().billingConfigurationId(NEW_CONFIGURATION_ID)
                        .timesheets(List.of(timesheet(true))).expenses(List.of(converted)).build());
        when(billingSnapshotRepository.saveAndFlush(any())).thenThrow(new RuntimeException("insert failed"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).startsWith("Failed to save Billing Snapshot");
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        // The acquisition record is only written after the snapshot - never for a snapshot that was not saved.
        verify(billingAcquisitionService, never()).createManualAcquisition(any(), any(), any(), any(), any());
    }

    @Test
    void createBillingSnapshot_acquisitionRecordRejected_rollsBackSnapshotAndPropagatesMessage() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        stubAcquiredTimesheets(configuration, request, timesheet(true));
        when(billingSnapshotRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingAcquisitionService.createManualAcquisition(
                eq(NEW_CONFIGURATION_ID), eq(PERIOD_START), eq(PERIOD_END), any(), eq("READY")))
                .thenThrow(new GlobalExceptionHandler.ValidationException(
                        "Billing Configuration is not active for project ID: 23"));

        assertThatThrownBy(() -> service.createBillingSnapshot(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Billing Configuration is not active for project ID: 23");

        // Snapshot insert is undone with the failed acquisition record.
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    // Test 2 - existing snapshot without an acquisition record
    @Test
    void createBillingSnapshot_existingSnapshotWithoutAcquisitionRecord_reconcilesAndReturnsIt() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(approvedTimeAndMaterialConfiguration(orphanedConfigurationId));
        when(billingAcquisitionService.recordAcquisitionForSnapshot(
                orphanedConfigurationId, PERIOD_START, PERIOD_END, snapshotId))
                .thenReturn(acquisition(snapshotId, "READY"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(newRequest());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getExistingSnapshot()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getData().getSnapshotNumber()).isEqualTo("BS-20260805120000");
        assertThat(response.getData().getAcquisitionStatus()).isEqualTo("READY");

        verify(billingAcquisitionService).recordAcquisitionForSnapshot(
                orphanedConfigurationId, PERIOD_START, PERIOD_END, snapshotId);
        // No duplicate snapshot, no re-acquisition from TMS.
        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verify(billingSnapshotRepository, never()).save(any());
        verify(timeAndMaterialStrategy, never()).acquire(any(), any());
        verify(billingAcquisitionService, never()).createManualAcquisition(any(), any(), any(), any(), anyString());
    }

    // Test 3 - existing snapshot whose lifecycle has progressed
    @ParameterizedTest
    @EnumSource(value = BillingSnapshotStatus.class, names = {"TAX_COMPLETED", "INVOICED"})
    void createBillingSnapshot_existingProgressedSnapshot_isReturnedUnchanged(BillingSnapshotStatus status) {
        existingSnapshot.setStatus(status);
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(approvedTimeAndMaterialConfiguration(orphanedConfigurationId));
        when(billingAcquisitionService.recordAcquisitionForSnapshot(
                orphanedConfigurationId, PERIOD_START, PERIOD_END, snapshotId))
                .thenReturn(acquisition(snapshotId, "READY"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(newRequest());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getStatus()).isEqualTo(status);
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getData().getSnapshotNumber()).isEqualTo("BS-20260805120000");
        assertThat(response.getData().getTotalAmount()).isEqualByComparingTo("10000");

        assertThat(existingSnapshot.getStatus()).isEqualTo(status);
        assertThat(existingSnapshot.getId()).isEqualTo(snapshotId);
        assertThat(existingSnapshot.getSnapshotNumber()).isEqualTo("BS-20260805120000");
        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verify(billingSnapshotRepository, never()).save(any());
        verify(timeAndMaterialStrategy, never()).acquire(any(), any());
    }

    @Test
    void createBillingSnapshot_existingSnapshot_configurationNoLongerRecordable_stillReturnsSnapshot() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(approvedTimeAndMaterialConfiguration(orphanedConfigurationId));
        when(billingAcquisitionService.recordAcquisitionForSnapshot(
                orphanedConfigurationId, PERIOD_START, PERIOD_END, snapshotId))
                .thenThrow(new GlobalExceptionHandler.ValidationException(
                        "Billing Configuration is not active for project ID: 23"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(newRequest());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getExistingSnapshot()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);
    }

    // Test 4 - concurrent duplicate request loses the unique-key race
    @Test
    void createBillingSnapshot_concurrentDuplicate_reReadsAndReusesWinningSnapshot() {
        BillingSnapshotCreateRequestDto request = newRequest();
        // The concurrent winner is the same configuration's snapshot.
        existingSnapshot.setBillingConfigurationId(NEW_CONFIGURATION_ID);
        // Not there on the first check; committed by the concurrent request by the re-read.
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END))
                .thenReturn(Optional.empty(), Optional.of(existingSnapshot));
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        stubAcquiredTimesheets(configuration, request, timesheet(true));
        when(billingSnapshotRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry for key 'uk_billing_snapshot_project_period'"));
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(NEW_CONFIGURATION_ID))
                .thenReturn(approvedTimeAndMaterialConfiguration(NEW_CONFIGURATION_ID));
        when(billingAcquisitionService.recordAcquisitionForSnapshot(
                NEW_CONFIGURATION_ID, PERIOD_START, PERIOD_END, snapshotId))
                .thenReturn(acquisition(snapshotId, "READY"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getExistingSnapshot()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);

        // The losing transaction was rolled back; its acquisition was never
        // recorded, and the winner's record is reconciled instead.
        verify(transactionManager).rollback(any());
        verify(billingAcquisitionService, never()).createManualAcquisition(any(), any(), any(), any(), anyString());
        verify(billingAcquisitionService, times(1)).recordAcquisitionForSnapshot(
                NEW_CONFIGURATION_ID, PERIOD_START, PERIOD_END, snapshotId);
    }

    @Test
    void createBillingSnapshot_integrityViolationWithoutMatchingSnapshot_returnsFailure() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        stubAcquiredTimesheets(configuration, request, timesheet(true));
        when(billingSnapshotRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Column 'currency_id' cannot be null"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(
                "Failed to save Billing Snapshot: Column 'currency_id' cannot be null");
        verify(transactionManager).rollback(any());
        verify(billingAcquisitionService, never()).recordAcquisitionForSnapshot(any(), any(), any(), any());
    }

    // Test 7 - readiness validation failure
    @Test
    void createBillingSnapshot_readinessValidationFails_returnsMessageAndPersistsNothing() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        stubAcquiredTimesheets(configuration, request, timesheet(false));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Timesheet [TMS-100] is not approved.");
        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verifyNoInteractions(billingAcquisitionService, transactionManager);
    }

    // Test 8 - TMS source-data retrieval failure
    @Test
    void createBillingSnapshot_tmsSourceFailure_propagatesAndRecordsNoAcquisition() {
        BillingSnapshotCreateRequestDto request = newRequest();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        when(timeAndMaterialStrategy.acquire(configuration, request))
                .thenThrow(new IllegalStateException("TMS service failed with status 503."));

        assertThatThrownBy(() -> service.createBillingSnapshot(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TMS service failed with status 503.");

        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verifyNoInteractions(billingAcquisitionService, transactionManager);
    }

    // =========================================================
    // Configuration-scoped snapshot resolution (stale INVOICED snapshot bug)
    // =========================================================

    // Scenarios E/F - a snapshot left by another configuration of the same
    // project and period must not be handed to the new configuration.
    @Test
    void createBillingSnapshot_snapshotOfOtherConfiguration_isRejectedNotReused() {
        existingSnapshot.setStatus(BillingSnapshotStatus.INVOICED);
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID)
                .billingConfigurationId(NEW_CONFIGURATION_ID)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .build();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage())
                .contains("BS-20260805120000", "INVOICED", orphanedConfigurationId.toString());
        verifyNoInteractions(billingAcquisitionService);
        verify(billingSnapshotRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).saveAndFlush(any());
        verify(timeAndMaterialStrategy, never()).acquire(any(), any());
    }

    // Same, when the request names no configuration: the project's approved one is used.
    @Test
    void createBillingSnapshot_snapshotOfOtherConfiguration_noConfigIdInRequest_usesActiveConfiguration() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));
        when(billingConfigurationIntegration.getApprovedBillingConfiguration(PROJECT_ID))
                .thenReturn(approvedTimeAndMaterialConfiguration(NEW_CONFIGURATION_ID));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(newRequest());

        assertThat(response.isSuccess()).isFalse();
        verifyNoInteractions(billingAcquisitionService);
    }

    // Scenario G - the same configuration's own snapshot is still reused (duplicate prevention).
    @Test
    void createBillingSnapshot_snapshotOfSameConfiguration_isStillReused() {
        existingSnapshot.setStatus(BillingSnapshotStatus.INVOICED);
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID)
                .billingConfigurationId(orphanedConfigurationId)
                .billingPeriodStart(PERIOD_START)
                .billingPeriodEnd(PERIOD_END)
                .build();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(approvedTimeAndMaterialConfiguration(orphanedConfigurationId));
        when(billingAcquisitionService.recordAcquisitionForSnapshot(
                orphanedConfigurationId, PERIOD_START, PERIOD_END, snapshotId))
                .thenReturn(acquisition(snapshotId, "READY"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getExistingSnapshot()).isTrue();
        assertThat(response.getData().getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
    }

    // Scenario D - an invoiced older period does not block a different period.
    @Test
    void createBillingSnapshot_differentPeriodAfterInvoicedPeriod_isAcquired() {
        BillingSnapshotCreateRequestDto request = BillingSnapshotCreateRequestDto.builder()
                .projectId(PROJECT_ID)
                .billingPeriodStart(PERIOD_END.plusDays(1))
                .billingPeriodEnd(PERIOD_END.plusMonths(1))
                .build();
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, request.getBillingPeriodStart(), request.getBillingPeriodEnd()))
                .thenReturn(Optional.empty());
        BillingConfigurationResponseDto configuration = stubConfigurationAndJurisdictions();
        stubAcquiredTimesheets(configuration, request, timesheet(true));
        when(billingSnapshotRepository.saveAndFlush(any(BillingSnapshot.class)))
                .thenAnswer(invocation -> {
                    BillingSnapshot saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });
        when(billingAcquisitionService.createManualAcquisition(any(), any(), any(), any(), anyString()))
                .thenReturn(acquisition(UUID.randomUUID(), "READY"));

        ApiResponse<BillingSnapshotResponseDto> response = service.createBillingSnapshot(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getExistingSnapshot()).isFalse();
        assertThat(response.getData().getStatus()).isEqualTo(BillingSnapshotStatus.READY_FOR_TAX);
    }

    @Test
    void getByProjectAndPeriod_snapshotOfOtherConfiguration_returnsNotFound() {
        existingSnapshot.setStatus(BillingSnapshotStatus.INVOICED);
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));

        ApiResponse<BillingSnapshotResponseDto> response =
                service.getByProjectAndPeriod(PROJECT_ID, PERIOD_START, PERIOD_END, NEW_CONFIGURATION_ID);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
    }

    @Test
    void getByProjectAndPeriod_snapshotOfSameConfiguration_isReturned() {
        when(billingSnapshotRepository.findByProjectIdAndBillingPeriodStartAndBillingPeriodEnd(
                PROJECT_ID, PERIOD_START, PERIOD_END)).thenReturn(Optional.of(existingSnapshot));
        when(billingConfigurationIntegration.getApprovedBillingConfigurationById(orphanedConfigurationId))
                .thenReturn(approvedTimeAndMaterialConfiguration(orphanedConfigurationId));

        ApiResponse<BillingSnapshotResponseDto> response = service.getByProjectAndPeriod(
                PROJECT_ID, PERIOD_START, PERIOD_END, orphanedConfigurationId);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getSnapshotId()).isEqualTo(snapshotId);
    }

    // Test 9 - snapshot numbers generated within the same second are distinct
    @Test
    void generateSnapshotNumber_manyWithinSameSecond_areDistinctAndFitColumn() {
        int count = 1_000;
        Set<String> numbers = new HashSet<>();
        Set<String> seconds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String number = service.generateSnapshotNumber();
            assertThat(number).matches("BS-\\d{14}-[0-9A-F]{8}").hasSizeLessThanOrEqualTo(30);
            numbers.add(number);
            seconds.add(number.substring(0, 17));
        }

        assertThat(numbers).hasSize(count);
        // Sanity check: the loop really did produce several numbers per second.
        assertThat(seconds.size()).isLessThan(count);
    }
}
