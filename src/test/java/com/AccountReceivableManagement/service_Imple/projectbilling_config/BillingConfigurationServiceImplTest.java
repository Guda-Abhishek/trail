package com.AccountReceivableManagement.service_Imple.projectbilling_config;

import com.AccountReceivableManagement.entity.client_entity.Client;
import com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingMilestonePlanResponseDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingPaymentEntryResponseDto;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTypeMaster;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.ApprovalStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.PaymentStructure;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotRepository;
import com.AccountReceivableManagement.repo.client.ClientRepository;
import com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.*;
import com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingMilestonePlanService;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingPeriodCalculatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingConfigurationServiceImplTest {

    @Mock private BillingConfigurationRepository billingConfigurationRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private ProjectMasterReferenceRepository projectRepository;
    @Mock private BillingTypeMasterRepository billingTypeRepository;
    @Mock private CurrencyMasterRepository currencyRepository;
    @Mock private PaymentTermsMasterRepository paymentTermsRepository;
    @Mock private BillingFrequencyMasterRepository billingFrequencyRepository;
    @Mock private TaxRegionMasterRepository taxRegionRepository;
    @Mock private BillingTMRateCardRepository billingTMRateCardRepository;
    @Mock private BillingFixedPriceRepository billingFixedPriceRepository;
    @Mock private BillingRecurringConfigurationRepository billingRecurringConfigurationRepository;
    @Mock private ProjectMasterReferenceRepository projectMasterReferenceRepository;
    @Mock private BillingScheduleRepository billingScheduleRepository;
    @Mock private BillingSnapshotRepository billingSnapshotRepository;
    @Mock private BillingOccurrenceServiceImpl billingOccurrenceService;
    @Mock private ProjectEligibilityRepository projectEligibilityRepository;
    @Mock private BillingPeriodCalculatorService billingPeriodCalculatorService;
    @Mock private BillingMilestonePlanRepository billingMilestonePlanRepository;
    @Mock private BillingPaymentEntryRepository billingPaymentEntryRepository;
    @Mock private BillingMilestonePlanService billingMilestonePlanService;
    @Mock private BillingConfigurationChangeTrackingService changeTrackingService;
    @Mock private RecordActionLockService recordActionLockService;
    @Mock private BillingConfigurationSnapshotRepository snapshotRepository;

    private BillingConfigurationServiceImpl service;

    private UUID configurationId;
    private BillingConfiguration draftConfiguration;

    @BeforeEach
    void setUp() {
        service = new BillingConfigurationServiceImpl(
                billingConfigurationRepository,
                clientRepository,
                projectRepository,
                billingTypeRepository,
                currencyRepository,
                paymentTermsRepository,
                billingFrequencyRepository,
                taxRegionRepository,
                billingTMRateCardRepository,
                billingFixedPriceRepository,
                billingRecurringConfigurationRepository,
                projectMasterReferenceRepository,
                billingScheduleRepository,
                billingSnapshotRepository,
                billingOccurrenceService,
                projectEligibilityRepository,
                billingPeriodCalculatorService,
                billingMilestonePlanRepository,
                billingPaymentEntryRepository,
                billingMilestonePlanService,
                changeTrackingService,
                recordActionLockService,
                snapshotRepository);

        configurationId = UUID.randomUUID();
        draftConfiguration = BillingConfiguration.builder()
                .billingConfigurationId(configurationId)
                .approvalStatus(ApprovalStatus.DRAFT)
                .build();
    }

    // =========================================================
    // E. Referenced by a historical BillingSnapshot -> rejected
    // =========================================================

    @Test
    void deleteBillingConfiguration_referencedBySnapshot_rejectsDeletion() {
        when(billingConfigurationRepository.findById(configurationId))
                .thenReturn(Optional.of(draftConfiguration));
        when(billingSnapshotRepository.existsByBillingConfigurationId(configurationId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deleteBillingConfiguration(configurationId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessageContaining("referenced by historical Billing Snapshot data");

        verify(billingConfigurationRepository, never()).delete(any());
        verify(billingFixedPriceRepository, never()).deleteByBillingConfiguration(any());
        verify(billingRecurringConfigurationRepository, never()).deleteByBillingConfiguration(any());
        verify(billingScheduleRepository, never()).deleteByBillingConfiguration(any());
    }

    // =========================================================
    // F. Unreferenced draft configuration -> deletion still works
    // =========================================================

    @Test
    void deleteBillingConfiguration_unreferencedDraftConfiguration_stillDeletes() {
        when(billingConfigurationRepository.findById(configurationId))
                .thenReturn(Optional.of(draftConfiguration));
        when(billingSnapshotRepository.existsByBillingConfigurationId(configurationId))
                .thenReturn(false);

        service.deleteBillingConfiguration(configurationId);

        verify(billingFixedPriceRepository, times(1)).deleteByBillingConfiguration(draftConfiguration);
        verify(billingRecurringConfigurationRepository, times(1)).deleteByBillingConfiguration(draftConfiguration);
        verify(billingScheduleRepository, times(1)).deleteByBillingConfiguration(draftConfiguration);
        verify(billingConfigurationRepository, times(1)).delete(draftConfiguration);
    }

    @Test
    void deleteBillingConfiguration_notDraft_rejectsRegardlessOfSnapshotReference() {
        BillingConfiguration approvedConfiguration = BillingConfiguration.builder()
                .billingConfigurationId(configurationId)
                .approvalStatus(ApprovalStatus.APPROVED)
                .build();

        when(billingConfigurationRepository.findById(configurationId))
                .thenReturn(Optional.of(approvedConfiguration));

        assertThatThrownBy(() -> service.deleteBillingConfiguration(configurationId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessageContaining("Only draft billing configurations can be deleted.");

        verify(billingSnapshotRepository, never()).existsByBillingConfigurationId(any());
        verify(billingConfigurationRepository, never()).delete(any());
    }

    // =========================================================
    // APPROVE - occurrence reconciliation is atomic with approval
    // =========================================================

    private BillingConfiguration pendingConfiguration() {
        return BillingConfiguration.builder()
                .billingConfigurationId(configurationId)
                .approvalStatus(ApprovalStatus.PENDING_APPROVAL)
                .billingStatus(com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingConfigurationStatus.INACTIVE)
                .effectiveFrom(java.time.LocalDate.now().minusDays(1))
                .build();
    }

    private void stubApprovableConfiguration(BillingConfiguration configuration) {
        when(billingConfigurationRepository.findById(configurationId)).thenReturn(Optional.of(configuration));
        when(billingConfigurationRepository.save(any(BillingConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void approve_pendingConfiguration_becomesApprovedActiveAndReconcilesThenPromotesOccurrences() {
        BillingConfiguration configuration = pendingConfiguration();
        stubApprovableConfiguration(configuration);

        service.approve(configurationId);

        assertThat(configuration.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(configuration.getBillingStatus())
                .isEqualTo(com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingConfigurationStatus.ACTIVE);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(billingOccurrenceService);
        inOrder.verify(billingOccurrenceService).reconcileOccurrencesOnConfigurationUpdate(configurationId);
        inOrder.verify(billingOccurrenceService).promoteDueOccurrencesToTaxPending(configurationId);
    }

    @Test
    void approve_notPending_isRejectedWithoutTouchingOccurrences() {
        when(billingConfigurationRepository.findById(configurationId)).thenReturn(Optional.of(draftConfiguration));

        assertThatThrownBy(() -> service.approve(configurationId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only configurations pending approval can be approved.");

        verifyNoInteractions(billingOccurrenceService);
    }

    // The reconciliation failure must propagate (rolling the approval back) instead of being
    // swallowed inside the transaction, which is what produced "marked as rollback-only".
    @Test
    void approve_occurrenceConstraintViolation_propagatesAsBusinessErrorInsteadOfBeingSwallowed() {
        stubApprovableConfiguration(pendingConfiguration());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement",
                        new java.sql.SQLException("Duplicate entry for key 'uk_billing_schedule_period'")))
                .when(billingOccurrenceService).reconcileOccurrencesOnConfigurationUpdate(configurationId);

        assertThatThrownBy(() -> service.approve(configurationId))
                .isInstanceOf(GlobalExceptionHandler.DuplicateResourceException.class)
                .hasMessageContaining("could not be approved")
                .hasMessageContaining("uk_billing_schedule_period");

        verify(billingOccurrenceService, never()).promoteDueOccurrencesToTaxPending(any());
    }

    @Test
    void approve_unexpectedReconciliationFailure_propagatesWithRealCause() {
        stubApprovableConfiguration(pendingConfiguration());
        org.mockito.Mockito.doThrow(new IllegalStateException("query did not return a unique result"))
                .when(billingOccurrenceService).reconcileOccurrencesOnConfigurationUpdate(configurationId);

        assertThatThrownBy(() -> service.approve(configurationId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessageContaining("billing occurrences could not be generated")
                .hasMessageContaining("query did not return a unique result");
    }

    @Test
    void approve_businessValidationFailureFromReconciliation_isPassedThroughUnchanged() {
        stubApprovableConfiguration(pendingConfiguration());
        org.mockito.Mockito.doThrow(new GlobalExceptionHandler.ValidationException(
                        "Billing date is required for all payment entries"))
                .when(billingOccurrenceService).reconcileOccurrencesOnConfigurationUpdate(configurationId);

        assertThatThrownBy(() -> service.approve(configurationId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Billing date is required for all payment entries");
    }

    @Test
    void deleteBillingConfiguration_nonexistentId_throwsResourceNotFoundException() {
        when(billingConfigurationRepository.findById(configurationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBillingConfiguration(configurationId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class);
    }

    // =========================================================
    // Client country is read from the Client entity
    // =========================================================

    @Test
    void getBillingConfiguration_clientCountryComesFromClient_notProjectPrimaryLocation() {
        draftConfiguration.setClient(Client.builder()
                .clientId(UUID.randomUUID())
                .clientName("Aditya Teja")
                .countryName("India")
                .countryCode("IN")
                .build());
        draftConfiguration.setProject(ProjectMasterReference.builder()
                .pmsProjectId(23L)
                .primaryLocation("Domestic")
                .build());
        when(billingConfigurationRepository.findById(configurationId))
                .thenReturn(Optional.of(draftConfiguration));

        var response = service.getBillingConfiguration(configurationId);

        assertThat(response.getCountryName()).isEqualTo("India");
        assertThat(response.getCountryCode()).isEqualTo("IN");
    }

    @Test
    void getBillingConfiguration_clientCountryNull_isNullEvenWhenProjectHasLocation() {
        draftConfiguration.setClient(Client.builder()
                .clientId(UUID.randomUUID())
                .clientName("Aditya Teja")
                .build());
        draftConfiguration.setProject(ProjectMasterReference.builder()
                .pmsProjectId(23L)
                .primaryLocation("Domestic")
                .build());
        when(billingConfigurationRepository.findById(configurationId))
                .thenReturn(Optional.of(draftConfiguration));

        var response = service.getBillingConfiguration(configurationId);

        assertThat(response.getCountryName()).isNull();
        assertThat(response.getCountryCode()).isNull();
    }

    // =========================================================
    // Pending approvals: Milestone Plan payment structure
    // =========================================================

    private BillingConfiguration pendingConfiguration(String billingTypeName) {
        BillingConfiguration configuration = BillingConfiguration.builder()
                .billingConfigurationId(UUID.randomUUID())
                .approvalStatus(ApprovalStatus.PENDING_APPROVAL)
                .billingType(BillingTypeMaster.builder().billingTypeName(billingTypeName).build())
                .build();
        when(billingConfigurationRepository.findByApprovalStatus(ApprovalStatus.PENDING_APPROVAL))
                .thenReturn(List.of(configuration));
        return configuration;
    }

    private BillingMilestonePlanResponseDto milestonePlan(PaymentStructure structure, BigDecimal... percentages) {
        List<BillingPaymentEntryResponseDto> entries = new java.util.ArrayList<>();
        for (int i = 0; i < percentages.length; i++) {
            entries.add(BillingPaymentEntryResponseDto.builder()
                    .sequence(i + 1)
                    .percentage(percentages[i])
                    .amount(new BigDecimal("95000.00").multiply(percentages[i]).divide(new BigDecimal("100")))
                    .billingDate(LocalDate.of(2026, 10, 12).plusDays(i))
                    .build());
        }
        return BillingMilestonePlanResponseDto.builder()
                .paymentStructure(structure)
                .totalContractValue(new BigDecimal("95000.00"))
                .entries(entries)
                .build();
    }

    @Test
    void getPendingApprovals_milestoneInstallmentsBeforeFirstApproval_exposesPaymentStructureAndEntries() {
        BillingConfiguration configuration = pendingConfiguration("Milestone Plan");
        when(billingMilestonePlanService.findActiveByBillingConfiguration(configuration))
                .thenReturn(Optional.of(milestonePlan(PaymentStructure.INSTALLMENTS,
                        new BigDecimal("40"), new BigDecimal("20"), new BigDecimal("40"))));

        var response = service.getPendingApprovals().get(0);

        assertThat(response.getPaymentStructure()).isEqualTo(PaymentStructure.INSTALLMENTS);
        assertThat(response.getMilestonePlanDetails().getEntries())
                .extracting(BillingPaymentEntryResponseDto::getPercentage)
                .containsExactly(new BigDecimal("40"), new BigDecimal("20"), new BigDecimal("40"));
        // Occurrences are only generated on approval
        assertThat(response.getMilestoneSchedules()).isEmpty();
    }

    @Test
    void getPendingApprovals_milestoneFullPayment_isNotReportedAsInstallments() {
        BillingConfiguration configuration = pendingConfiguration("Milestone Plan");
        when(billingMilestonePlanService.findActiveByBillingConfiguration(configuration))
                .thenReturn(Optional.of(milestonePlan(PaymentStructure.FULL_PAYMENT, new BigDecimal("100"))));

        var response = service.getPendingApprovals().get(0);

        assertThat(response.getPaymentStructure()).isEqualTo(PaymentStructure.FULL_PAYMENT);
        assertThat(response.getMilestonePlanDetails().getEntries()).hasSize(1);
    }

    @Test
    void getPendingApprovals_recurring_hasNoMilestonePaymentStructure() {
        pendingConfiguration("Recurring");

        var response = service.getPendingApprovals().get(0);

        assertThat(response.getPaymentStructure()).isNull();
        assertThat(response.getMilestonePlanDetails()).isNull();
        verifyNoInteractions(billingMilestonePlanService);
    }

    @Test
    void getPendingApprovals_timesheetBased_hasNoMilestonePaymentStructure() {
        pendingConfiguration("Timesheet Based");

        var response = service.getPendingApprovals().get(0);

        assertThat(response.getPaymentStructure()).isNull();
        assertThat(response.getMilestonePlanDetails()).isNull();
        verifyNoInteractions(billingMilestonePlanService);
    }
}
