package com.AccountReceivableManagement.service_Imple.invoice_generation;

import com.AccountReceivableManagement.dto.company_profile.CompanyProfileResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceApprovalHistoryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceApprovalSummaryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceApprovalWorkspaceResponseDto;
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
import com.AccountReceivableManagement.entity.client_entity.Client;
import com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingSchedule;
import com.AccountReceivableManagement.entity.projectbilling_config.PaymentTermsMaster;
import com.AccountReceivableManagement.entity.tax_calculation.TaxCalculation;
import com.AccountReceivableManagement.entity.tax_calculation.TaxCalculationComponent;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceApprovalAction;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingPeriodStatus;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxApplicabilityType;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxCalculationStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceApprovalHistoryRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.BillingScheduleRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.PaymentTermsMasterRepository;
import com.AccountReceivableManagement.repo.tax_calculation.TaxCalculationRepository;
import com.AccountReceivableManagement.service_interface.company_profile.CompanyProfileService;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceApprovalHistoryRepository invoiceApprovalHistoryRepository;

    @Mock
    private BillingSnapshotRepository billingSnapshotRepository;

    @Mock
    private TaxCalculationRepository taxCalculationRepository;

    @Mock
    private BillingConfigurationService billingConfigurationService;

    @Mock
    private PaymentTermsMasterRepository paymentTermsMasterRepository;

        @Mock
        private BillingScheduleRepository billingScheduleRepository;

        @Mock
        private CompanyProfileService companyProfileService;

    @Mock
    private com.AccountReceivableManagement.service_interface.concurrency_approval.RecordActionLockService recordActionLockService;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    private UUID snapshotId;
    private UUID clientId;
    private UUID paymentTermId;

    @BeforeEach
    void setUp() {
        snapshotId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        paymentTermId = UUID.randomUUID();

        // Default stub for every generation-path test below, which needs
        // an active Company Profile to exist but isn't itself testing the
        // seller-snapshot behavior. Tests that ARE testing it override this
        // with their own when(...) stub.
        lenient().when(companyProfileService.getActive()).thenReturn(activeCompanyProfile());
    }

    private CompanyProfileResponseDto activeCompanyProfile() {
        return CompanyProfileResponseDto.builder()
                .companyProfileId(UUID.randomUUID())
                .legalName("Paves Technologies Pvt Ltd")
                .addressLine1("Plot 12, Tech Park")
                .addressLine2("Phase 2")
                .city("Hyderabad")
                .state("Telangana")
                .postalCode("500081")
                .country("India")
                .gstin("36AAAAA0000A1Z5")
                .email("billing@paves.example")
                .phone("+91 40 1234 5678")
                .logoReference("logos/paves.png")
                .authorizedSignatureReference("sig-asset-1")
                .defaultInvoiceNotes("TEST-NOTES-V1")
                .defaultTermsAndConditions("TEST-TERMS-V1")
                .defaultPaymentInstructions("TEST-PAYMENT-V1")
                .isActive(true)
                .build();
    }

    private BillingSnapshot taxCompletedSnapshot() {
        BillingSnapshot snapshot = BillingSnapshot.builder()
                .id(snapshotId)
                .snapshotNumber("BS-20260908164549")
                .clientId(clientId)
                .projectId(23L)
                .billingConfigurationId(UUID.randomUUID())
                .currencyCode("USD")
                .paymentTermId(paymentTermId)
                .paymentTermCode("NET_30")
                .billingPeriodStart(LocalDate.of(2026, 6, 1))
                .billingPeriodEnd(LocalDate.of(2026, 8, 30))
                .status(BillingSnapshotStatus.TAX_COMPLETED)
                .subtotal(new BigDecimal("5500.00"))
                .expenseAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("5500.00"))
                .build();

        snapshot.getItems().add(
                BillingSnapshotItem.builder()
                        .billingSnapshotItemId(UUID.randomUUID())
                        .billingSnapshot(snapshot)
                        .itemType(BillingItemType.TIME_ENTRY)
                        .itemName("Backend Development")
                        .sourceReferenceId("TMS-1001")
                        .quantity(new BigDecimal("55.00"))
                        .rate(new BigDecimal("100.00"))
                        .amount(new BigDecimal("5500.00"))
                        .workDate(LocalDate.of(2026, 7, 15))
                        .role("Developer")
                        .build()
        );

        return snapshot;
    }

    private TaxCalculation completedTaxCalculation() {
        TaxCalculation calculation = TaxCalculation.builder()
                .taxCalculationId(UUID.randomUUID())
                .billingSnapshotId(snapshotId)
                .taxRegionId(UUID.randomUUID())
                .taxConfigurationId(UUID.randomUUID())
                .taxableAmount(new BigDecimal("5500.00"))
                .totalTaxAmount(new BigDecimal("990.00"))
                .grandTotal(new BigDecimal("6490.00"))
                .status(TaxCalculationStatus.CALCULATED)
                .calculatedAt(LocalDateTime.now())
                .components(new ArrayList<>())
                .build();

        TaxCalculationComponent cgst = TaxCalculationComponent.builder()
                .taxCalculationComponentId(UUID.randomUUID())
                .taxCalculation(calculation)
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("CGST")
                .taxTypeName("Central GST")
                .appliedRate(new BigDecimal("9.0000"))
                .taxAmount(new BigDecimal("495.00"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .build();

        TaxCalculationComponent sgst = TaxCalculationComponent.builder()
                .taxCalculationComponentId(UUID.randomUUID())
                .taxCalculation(calculation)
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("SGST")
                .taxTypeName("State GST")
                .appliedRate(new BigDecimal("9.0000"))
                .taxAmount(new BigDecimal("495.00"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .build();

        calculation.getComponents().add(cgst);
        calculation.getComponents().add(sgst);

        return calculation;
    }

    private BillingConfigurationResponseDto configuration() {
        return BillingConfigurationResponseDto.builder()
                .projectName("Website Redesign")
                .clientName("Account Management")
                .clientId(clientId)
                .countryCode("+91")
                .email("client@example.com")
                .phone("9876543210")
                .projectStartDate(LocalDate.of(2026, 8, 19))
                .projectEndDate(LocalDate.of(2026, 10, 7))
                .build();
    }

    private PaymentTermsMaster paymentTerms() {
        return PaymentTermsMaster.builder()
                .paymentTermId(paymentTermId)
                .paymentTermName("Net 30")
                .paymentDays(30)
                .isActive(true)
                .build();
    }

    private BillingSchedule taxCalculatedSchedule() {
        UUID scheduleId = UUID.randomUUID();
        BillingConfiguration billingConfiguration = BillingConfiguration.builder()
                .billingConfigurationId(UUID.randomUUID())
                .client(Client.builder().clientId(clientId).build())
                .project(ProjectMasterReference.builder().pmsProjectId(23L).build())
                .build();

        return BillingSchedule.builder()
                .billingScheduleId(scheduleId)
                .billingConfiguration(billingConfiguration)
                .periodNumber(2)
                .periodStartDate(LocalDate.of(2026, 7, 1))
                .periodEndDate(LocalDate.of(2026, 7, 31))
                .billingDate(LocalDate.of(2026, 7, 31))
                .billingAmount(new BigDecimal("2500.00"))
                .periodStatus(BillingPeriodStatus.TAX_CALCULATED)
                .taxStatus(BillingPeriodStatus.TAX_CALCULATED)
                .isInvoiced(false)
                .isActive(true)
                .build();
    }

    private TaxCalculation completedScheduleTaxCalculation(UUID scheduleId) {
        TaxCalculation calculation = completedTaxCalculation();
        calculation.setBillingSnapshotId(null);
        calculation.setBillingScheduleId(scheduleId);
        calculation.setTaxableAmount(new BigDecimal("2500.00"));
        calculation.setTotalTaxAmount(new BigDecimal("450.00"));
        calculation.setGrandTotal(new BigDecimal("2950.00"));
        calculation.getComponents().get(0).setTaxAmount(new BigDecimal("225.00"));
        calculation.getComponents().get(1).setTaxAmount(new BigDecimal("225.00"));
        return calculation;
    }

    @Test
    void generateInvoiceForSchedule_taxCalculatedOccurrence_createsFixedPriceInvoice() {
        BillingSchedule schedule = taxCalculatedSchedule();
        TaxCalculation taxCalculation = completedScheduleTaxCalculation(schedule.getBillingScheduleId());

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(schedule));
        when(taxCalculationRepository.findByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any()))
                .thenReturn(BillingConfigurationResponseDto.builder()
                        .clientId(clientId)
                        .clientName("Account Management")
                        .projectId(23L)
                        .projectName("Website Redesign")
                        .currencyCode("USD")
                        .paymentTermId(paymentTermId)
                        .paymentTermCode("NET_30")
                        .projectStartDate(LocalDate.of(2026, 8, 19))
                        .projectEndDate(LocalDate.of(2026, 10, 7))
                        .build());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingScheduleRepository.save(any(BillingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId());

        // The number is assigned only here, at generation - a real INV- number, not derived from any id.
        assertThat(response.getInvoiceNumber()).isNotBlank().startsWith("INV-");
        assertThat(response.getInvoiceNumber())
                .doesNotContain(schedule.getBillingScheduleId().toString())
                .doesNotContain(clientId.toString());
        assertThat(response.getBillingSnapshotId()).isNull();
        assertThat(response.getBillingScheduleId()).isEqualTo(schedule.getBillingScheduleId());
        assertThat(response.getClientId()).isEqualTo(clientId);
        assertThat(response.getProjectId()).isEqualTo(23L);
        assertThat(response.getProjectStartDate()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(response.getProjectEndDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(response.getBillingPeriodStart()).isEqualTo(schedule.getPeriodStartDate());
        assertThat(response.getBillingPeriodEnd()).isEqualTo(schedule.getPeriodEndDate());
        assertThat(response.getSubtotal()).isEqualByComparingTo("2500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("450.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("2950.00");
        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getItemType()).isEqualTo(BillingItemType.FIXED_PRICE);
            assertThat(item.getQuantity()).isEqualByComparingTo("1");
            assertThat(item.getRate()).isEqualByComparingTo("2500.00");
            assertThat(item.getAmount()).isEqualByComparingTo("2500.00");
            assertThat(item.getSourceReferenceId()).isEqualTo(schedule.getBillingScheduleId().toString());
        });
        assertThat(response.getTaxComponents()).hasSize(2);
        assertThat(schedule.getIsInvoiced()).isTrue();
        assertThat(schedule.getPeriodStatus()).isEqualTo(BillingPeriodStatus.INVOICED);
        assertThat(schedule.getInvoiceDate()).isEqualTo(response.getInvoiceDate());
    }

    // Seller snapshot (fixed-price/schedule path): copied from the active
    // Company Profile at generation time, the same as the billing-snapshot
    // path. Client fields (Bill To) are untouched by this - separate concern.
    @Test
    void generateInvoiceForSchedule_copiesActiveCompanyProfileSellerSnapshot() {
        BillingSchedule schedule = taxCalculatedSchedule();
        TaxCalculation taxCalculation = completedScheduleTaxCalculation(schedule.getBillingScheduleId());

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(schedule));
        when(taxCalculationRepository.findByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any()))
                .thenReturn(BillingConfigurationResponseDto.builder()
                        .clientId(clientId).clientName("Account Management")
                        .projectId(23L).projectName("Website Redesign")
                        .currencyCode("USD").build());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingScheduleRepository.save(any(BillingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId());

        assertThat(response.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd");
        assertThat(response.getSellerAddressLine1()).isEqualTo("Plot 12, Tech Park");
        assertThat(response.getSellerCity()).isEqualTo("Hyderabad");
        assertThat(response.getSellerGstin()).isEqualTo("36AAAAA0000A1Z5");
        assertThat(response.getSellerEmail()).isEqualTo("billing@paves.example");
        assertThat(response.getSellerPhone()).isEqualTo("+91 40 1234 5678");
        assertThat(response.getSellerLogoReference()).isEqualTo("logos/paves.png");
        assertThat(response.getSellerSignatureReference()).isEqualTo("sig-asset-1");

        // Client/CDC-derived fields are a separate concern and unaffected.
        assertThat(response.getClientId()).isEqualTo(clientId);
    }

    @Test
    void generateInvoiceForSchedule_alreadyInvoicedOccurrence_rejectsDuplicate() {
        BillingSchedule schedule = taxCalculatedSchedule();
        schedule.setIsInvoiced(true);

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId()))
                .isInstanceOf(GlobalExceptionHandler.DuplicateResourceException.class);
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void generateInvoiceForSchedule_withoutCompletedTax_rejectsGeneration() {
        BillingSchedule schedule = taxCalculatedSchedule();
        schedule.setPeriodStatus(BillingPeriodStatus.TAX_PENDING);

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId()))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class);
        verifyNoInteractions(taxCalculationRepository, invoiceRepository);
    }

    @Test
    void generateInvoiceForSchedule_generatedInvoice_canBeSubmittedForApproval() {
        BillingSchedule schedule = taxCalculatedSchedule();
        TaxCalculation taxCalculation = completedScheduleTaxCalculation(schedule.getBillingScheduleId());

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId())).thenReturn(Optional.of(schedule));
        when(taxCalculationRepository.findByBillingScheduleId(schedule.getBillingScheduleId())).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingScheduleId(schedule.getBillingScheduleId())).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(BillingConfigurationResponseDto.builder()
                .clientId(clientId).clientName("Account Management").projectId(23L).projectName("Website Redesign")
                .currencyCode("USD").build());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingScheduleRepository.save(any(BillingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto generated = invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId());
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = Invoice.builder().invoiceId(invoiceId).billingScheduleId(schedule.getBillingScheduleId())
                .status(InvoiceStatus.GENERATED).items(new ArrayList<>()).taxComponents(new ArrayList<>()).build();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(invoice)).thenReturn(invoice);

        InvoiceResponseDto submitted = invoiceService.submitForApproval(invoiceId);

        assertThat(generated.getStatus()).isEqualTo(InvoiceStatus.GENERATED);
        assertThat(submitted.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
    }

    // CASE 1/2/3/4/5/6/7/8/9/10 — Happy path: full invoice generation from the verified example.
    @Test
    void generateInvoice_taxCompletedSnapshot_generatesInvoiceAndCompletesTransition() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        // Invoice number generated by the backend.
        assertThat(response.getInvoiceNumber()).isNotBlank();
        assertThat(response.getInvoiceNumber()).startsWith("INV-");
        assertThat(response.getInvoiceNumber()).isNotEqualTo(snapshot.getSnapshotNumber());

        // Human-readable billing snapshot number exposed alongside the id, never fabricated.
        assertThat(response.getBillingSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");

        // Client / project information copied.
        assertThat(response.getClientId()).isEqualTo(clientId);
        assertThat(response.getClientName()).isEqualTo("Account Management");
        assertThat(response.getCountryCode()).isEqualTo("+91");
        assertThat(response.getEmail()).isEqualTo("client@example.com");
        assertThat(response.getPhone()).isEqualTo("9876543210");
        assertThat(response.getProjectId()).isEqualTo(23L);
        assertThat(response.getProjectName()).isEqualTo("Website Redesign");

        // Project duration copied from BillingConfiguration.
        assertThat(response.getProjectStartDate()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(response.getProjectEndDate()).isEqualTo(LocalDate.of(2026, 10, 7));

        // Billing period copied.
        assertThat(response.getBillingPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(response.getBillingPeriodEnd()).isEqualTo(LocalDate.of(2026, 8, 30));

        // Payment terms copied and due date derived from them.
        assertThat(response.getPaymentTermCode()).isEqualTo("NET_30");
        assertThat(response.getDueDate()).isEqualTo(response.getInvoiceDate().plusDays(30));

        // BillingSnapshotItems -> InvoiceItems.
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getItemName()).isEqualTo("Backend Development");
        assertThat(response.getItems().get(0).getAmount()).isEqualByComparingTo("5500.00");

        // TaxCalculationComponents -> InvoiceTaxComponents.
        assertThat(response.getTaxComponents()).hasSize(2);
        assertThat(response.getTaxComponents())
                .anySatisfy(c -> {
                    assertThat(c.getTaxTypeCode()).isEqualTo("CGST");
                    assertThat(c.getTaxAmount()).isEqualByComparingTo("495.00");
                })
                .anySatisfy(c -> {
                    assertThat(c.getTaxTypeCode()).isEqualTo("SGST");
                    assertThat(c.getTaxAmount()).isEqualByComparingTo("495.00");
                });

        // Financial totals copied as-is from TaxCalculation - never recalculated.
        assertThat(response.getSubtotal()).isEqualByComparingTo(taxCalculation.getTaxableAmount());
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo(taxCalculation.getTotalTaxAmount());
        assertThat(response.getGrandTotal()).isEqualByComparingTo(taxCalculation.getGrandTotal());
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.GENERATED);

        // BillingSnapshot transitions TAX_COMPLETED -> INVOICED, on the same instance
        // (no new BillingSnapshot is created).
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        verify(billingSnapshotRepository).save(snapshot);
        verify(billingSnapshotRepository, never()).save(argThat(s -> s != snapshot));

        // No tax recalculation occurs - the tax calculation is only ever read, never saved.
        verify(taxCalculationRepository, never()).save(any());
    }

    // Seller snapshot: copied from the active Company Profile at
    // generation time, exposed through InvoiceResponseDto under its own
    // seller* fields - a separate concern from the Client-derived fields
    // (clientId/clientName/billingAddress/etc.), which are untouched.
    @Test
    void generateInvoice_copiesActiveCompanyProfileSellerSnapshot() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd");
        assertThat(response.getSellerAddressLine1()).isEqualTo("Plot 12, Tech Park");
        assertThat(response.getSellerAddressLine2()).isEqualTo("Phase 2");
        assertThat(response.getSellerCity()).isEqualTo("Hyderabad");
        assertThat(response.getSellerState()).isEqualTo("Telangana");
        assertThat(response.getSellerPostalCode()).isEqualTo("500081");
        assertThat(response.getSellerCountry()).isEqualTo("India");
        assertThat(response.getSellerGstin()).isEqualTo("36AAAAA0000A1Z5");
        assertThat(response.getSellerEmail()).isEqualTo("billing@paves.example");
        assertThat(response.getSellerPhone()).isEqualTo("+91 40 1234 5678");
        assertThat(response.getSellerLogoReference()).isEqualTo("logos/paves.png");
        assertThat(response.getSellerSignatureReference()).isEqualTo("sig-asset-1");

        // Client-side fields are a separate concern, still sourced independently.
        assertThat(response.getClientId()).isEqualTo(clientId);
        assertThat(response.getClientName()).isEqualTo("Account Management");
    }

    // "Fail loudly rather than fabricate": if no Company Profile has been
    // configured, generation must not produce an invoice with blank/guessed
    // seller information - it must fail outright.
    @Test
    void generateInvoice_noActiveCompanyProfile_throwsResourceNotFoundExceptionAndPersistsNothing() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(companyProfileService.getActive())
                .thenThrow(new GlobalExceptionHandler.ResourceNotFoundException(
                        "No active company profile has been configured."
                ));

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("No active company profile has been configured.");

        verify(invoiceRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).save(any());
    }

    // Editing the Company Profile after an invoice was generated must not
    // change that invoice's already-frozen seller snapshot - each
    // generation call captures whatever was active *at that time* only.
    @Test
    void generateInvoice_companyProfileEditedAfterward_doesNotChangeExistingInvoiceSellerSnapshot() {
        BillingSnapshot firstSnapshot = taxCompletedSnapshot();
        TaxCalculation firstTaxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(firstSnapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(firstTaxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto firstInvoice = invoiceService.generateInvoice(snapshotId);
        assertThat(firstInvoice.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd");

        // Company Profile is now "edited" - the mock returns a different
        // legal name for any *subsequent* getActive() call.
        when(companyProfileService.getActive()).thenReturn(
                CompanyProfileResponseDto.builder()
                        .companyProfileId(UUID.randomUUID())
                        .legalName("Paves Technologies Pvt Ltd (Renamed)")
                        .isActive(true)
                        .build()
        );

        // The already-generated invoice, as persisted, is untouched by the edit.
        assertThat(firstInvoice.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd");

        // A new invoice generated now picks up the newly-active profile.
        UUID secondSnapshotId = UUID.randomUUID();
        BillingSnapshot secondSnapshot = taxCompletedSnapshot();
        secondSnapshot.setId(secondSnapshotId);
        TaxCalculation secondTaxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(secondSnapshotId)).thenReturn(Optional.of(secondSnapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(secondSnapshotId)).thenReturn(Optional.of(secondTaxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(secondSnapshotId)).thenReturn(false);

        InvoiceResponseDto secondInvoice = invoiceService.generateInvoice(secondSnapshotId);

        assertThat(secondInvoice.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd (Renamed)");
        // The first invoice's own response object remains exactly as generated.
        assertThat(firstInvoice.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd");
    }

    // Invoice content (Notes / Terms / Payment Instructions): copied from the
    // Company Profile defaults at generation and frozen on the invoice.
    @Test
    void generateInvoice_copiesInvoiceContentDefaultsOntoInvoice() {
        BillingSnapshot snapshot = taxCompletedSnapshot();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(completedTaxCalculation()));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        org.mockito.ArgumentCaptor<Invoice> saved = org.mockito.ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(saved.capture());
        assertThat(saved.getValue().getInvoiceNotes()).isEqualTo("TEST-NOTES-V1");
        assertThat(saved.getValue().getTermsAndConditions()).isEqualTo("TEST-TERMS-V1");
        assertThat(saved.getValue().getPaymentInstructions()).isEqualTo("TEST-PAYMENT-V1");

        assertThat(response.getInvoiceNotes()).isEqualTo("TEST-NOTES-V1");
        assertThat(response.getTermsAndConditions()).isEqualTo("TEST-TERMS-V1");
        assertThat(response.getPaymentInstructions()).isEqualTo("TEST-PAYMENT-V1");
    }

    @Test
    void generateInvoice_noDefaultsConfigured_leavesInvoiceContentNull() {
        BillingSnapshot snapshot = taxCompletedSnapshot();

        when(companyProfileService.getActive()).thenReturn(
                CompanyProfileResponseDto.builder()
                        .companyProfileId(UUID.randomUUID())
                        .legalName("Paves Technologies Pvt Ltd")
                        .isActive(true)
                        .build());
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(completedTaxCalculation()));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getInvoiceNotes()).isNull();
        assertThat(response.getTermsAndConditions()).isNull();
        assertThat(response.getPaymentInstructions()).isNull();
    }

    // Changing the defaults afterwards must not alter an already-generated
    // invoice; only invoices generated afterwards pick up the new values.
    @Test
    void generateInvoice_defaultsChangedAfterward_existingInvoiceKeepsSavedContent() {
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(taxCompletedSnapshot()));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(completedTaxCalculation()));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto first = invoiceService.generateInvoice(snapshotId);

        when(companyProfileService.getActive()).thenReturn(
                CompanyProfileResponseDto.builder()
                        .companyProfileId(UUID.randomUUID())
                        .legalName("Paves Technologies Pvt Ltd")
                        .defaultInvoiceNotes("TEST-NOTES-V2")
                        .defaultTermsAndConditions("TEST-TERMS-V2")
                        .defaultPaymentInstructions("TEST-PAYMENT-V2")
                        .isActive(true)
                        .build());

        // Re-reading the persisted first invoice returns its own saved content.
        org.mockito.ArgumentCaptor<Invoice> saved = org.mockito.ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(saved.capture());
        Invoice firstInvoice = saved.getValue();
        when(invoiceRepository.findById(any())).thenReturn(Optional.of(firstInvoice));
        InvoiceResponseDto reread = invoiceService.getInvoiceById(firstInvoice.getInvoiceId());

        assertThat(first.getInvoiceNotes()).isEqualTo("TEST-NOTES-V1");
        assertThat(reread.getInvoiceNotes()).isEqualTo("TEST-NOTES-V1");
        assertThat(reread.getTermsAndConditions()).isEqualTo("TEST-TERMS-V1");
        assertThat(reread.getPaymentInstructions()).isEqualTo("TEST-PAYMENT-V1");

        // A newly generated invoice picks up the new defaults.
        UUID secondSnapshotId = UUID.randomUUID();
        BillingSnapshot secondSnapshot = taxCompletedSnapshot();
        secondSnapshot.setId(secondSnapshotId);
        when(billingSnapshotRepository.findById(secondSnapshotId)).thenReturn(Optional.of(secondSnapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(secondSnapshotId)).thenReturn(Optional.of(completedTaxCalculation()));
        when(invoiceRepository.existsByBillingSnapshotId(secondSnapshotId)).thenReturn(false);

        assertThat(invoiceService.generateInvoice(secondSnapshotId).getInvoiceNotes()).isEqualTo("TEST-NOTES-V2");
    }

    @Test
    void generateInvoiceForSchedule_copiesInvoiceContentDefaultsOntoInvoice() {
        BillingSchedule schedule = taxCalculatedSchedule();
        TaxCalculation taxCalculation = completedScheduleTaxCalculation(schedule.getBillingScheduleId());

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(schedule));
        when(taxCalculationRepository.findByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any()))
                .thenReturn(BillingConfigurationResponseDto.builder()
                        .clientId(clientId).clientName("Account Management")
                        .projectId(23L).projectName("Website Redesign")
                        .currencyCode("USD").build());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingScheduleRepository.save(any(BillingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId());

        assertThat(response.getInvoiceNotes()).isEqualTo("TEST-NOTES-V1");
        assertThat(response.getTermsAndConditions()).isEqualTo("TEST-TERMS-V1");
        assertThat(response.getPaymentInstructions()).isEqualTo("TEST-PAYMENT-V1");
    }

    // Payment term name: frozen from the snapshot's own (frozen) payment term
    // name, alongside the pre-existing paymentTermCode (day-count) field.
    @Test
    void generateInvoice_paymentTermNameFrozenFromSnapshot() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setPaymentTermName("Net 30");
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getPaymentTermCode()).isEqualTo("NET_30");
        assertThat(response.getPaymentTermName()).isEqualTo("Net 30");
    }

    // Resource name: frozen from BillingSnapshotItem.resourceName (the
    // genuine TMS employee name) onto the corresponding InvoiceItem.
    @Test
    void generateInvoice_timeEntryItem_freezesResourceName() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.getItems().get(0).setResourceName("Jane Doe");
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getItems()).singleElement()
                .extracting("resourceName")
                .isEqualTo("Jane Doe");
    }

    // A line the rate schedule excluded stays on the snapshot for audit but never becomes an invoice item.
    @Test
    void generateInvoice_excludedTimeEntry_isNotAnInvoiceItem() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.getItems().add(BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID()).billingSnapshot(snapshot)
                .itemType(BillingItemType.TIME_ENTRY).itemName("Excluded Dev")
                .sourceReferenceId("TMS-EXCL").quantity(new BigDecimal("10.00"))
                .rate(new BigDecimal("80.00")).amount(new BigDecimal("800.00"))
                .workDate(LocalDate.of(2026, 7, 16)).role("Developer")
                .includeInInvoice(false).build());
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getItems()).singleElement()
                .extracting("sourceReferenceId").isEqualTo("TMS-1001");
        assertThat(snapshot.getItems()).hasSize(2);
    }

    // A snapshot holding labor + an XMS expense: the invoice carries each
    // snapshot item exactly once, and its subtotal is the tax calculation's
    // taxable amount (labor + expense, counted once - not re-added).
    @Test
    void generateInvoice_snapshotWithExpenseItem_includesExpenseExactlyOnce() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setExpenseAmount(new BigDecimal("50.00"));
        snapshot.setTotalAmount(new BigDecimal("5550.00"));
        snapshot.getItems().add(BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID()).billingSnapshot(snapshot)
                .itemType(BillingItemType.EXPENSE).itemName("Travel")
                .sourceReferenceId("XMS-L1").quantity(BigDecimal.ONE)
                .rate(new BigDecimal("50.00")).amount(new BigDecimal("50.00"))
                .workDate(LocalDate.of(2026, 7, 20)).sourceSystem("XMS").build());

        TaxCalculation taxCalculation = completedTaxCalculation();
        taxCalculation.setTaxableAmount(new BigDecimal("5550.00"));
        taxCalculation.setGrandTotal(new BigDecimal("6540.00"));

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().stream()
                .filter(i -> "XMS-L1".equals(i.getSourceReferenceId()))).hasSize(1);
        BigDecimal itemTotal = response.getItems().stream()
                .map(i -> i.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(itemTotal).isEqualByComparingTo("5550.00");
        assertThat(response.getSubtotal()).isEqualByComparingTo("5550.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6540.00");
    }

    // GET invoice by invoice id — new endpoint, reuses the same mapToResponse
    // as the snapshot-based GET, so it returns the identical full contract.
    @Test
    void getInvoiceById_existingInvoice_returnsFullResponse() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = Invoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber("INV-20260908164549")
                .status(InvoiceStatus.GENERATED)
                .items(new ArrayList<>())
                .taxComponents(new ArrayList<>())
                .build();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        InvoiceResponseDto response = invoiceService.getInvoiceById(invoiceId);

        assertThat(response.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260908164549");
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.GENERATED);
                assertThat(response.getCountryCode()).isNull();
                assertThat(response.getEmail()).isNull();
                assertThat(response.getPhone()).isNull();
    }

    @Test
    void getInvoiceById_missingInvoice_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.getInvoiceById(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class);
    }

    // CASE 11 — READY_FOR_TAX snapshot cannot generate an invoice.
    @Test
    void generateInvoice_snapshotNotTaxCompleted_throwsValidationException() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.READY_FOR_TAX);

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice cannot be generated because this billing snapshot has not completed tax calculation.");

        verify(invoiceRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).save(any());
    }

    // CASE 12 — Missing BillingSnapshot fails correctly.
    @Test
    void generateInvoice_snapshotNotFound_throwsResourceNotFoundException() {
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Billing snapshot could not be found.");

        verify(invoiceRepository, never()).save(any());
    }

    // CASE 13 — Missing TaxCalculation fails correctly.
    @Test
    void generateInvoice_taxCalculationMissing_throwsResourceNotFoundException() {
        BillingSnapshot snapshot = taxCompletedSnapshot();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("No tax calculation has been completed for this billing snapshot. Invoice cannot be generated.");

        verify(invoiceRepository, never()).save(any());
    }

    // CASE 14 — Existing invoice cannot be generated again (application-level check).
    @Test
    void generateInvoice_invoiceAlreadyExists_throwsDuplicateResourceException() {
        BillingSnapshot snapshot = taxCompletedSnapshot();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(true);

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.DuplicateResourceException.class)
                .hasMessage("An invoice has already been generated for this billing snapshot.");

        verify(invoiceRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).save(any());
    }

    // CASE 15 — Database uniqueness backstops the duplicate check under concurrent requests.
    @Test
    void generateInvoice_concurrentDuplicateAtSave_throwsDuplicateResourceException() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.DuplicateResourceException.class)
                .hasMessage("An invoice has already been generated for this billing snapshot.");

        // Snapshot must not flip to INVOICED when invoice persistence fails.
        verify(billingSnapshotRepository, never()).save(any());
        assertThat(snapshot.getStatus()).isNotEqualTo(BillingSnapshotStatus.INVOICED);
    }

    // A repeated request (double-click / retry / refresh) after a successful generation finds the
    // snapshot already INVOICED. It must be answered with the duplicate-invoice error, not with the
    // misleading "has not completed tax calculation" validation error.
    @Test
    void generateInvoice_retryAfterSuccess_snapshotAlreadyInvoiced_throwsDuplicateNotTaxIncomplete() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(true);

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.DuplicateResourceException.class)
                .hasMessage("An invoice has already been generated for this billing snapshot.");

        verify(invoiceRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).save(any());
        verifyNoInteractions(taxCalculationRepository, companyProfileService);
    }

    // The unique constraint is evaluated at flush, inside the service call, so the losing request of
    // two concurrent generations gets the duplicate-invoice error rather than a commit-time 500.
    @Test
    void generateInvoice_concurrentDuplicateAtFlush_throwsDuplicateResourceExceptionAndKeepsSnapshotUninvoiced() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_invoice_billing_snapshot'"))
                .when(invoiceRepository).flush();

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.DuplicateResourceException.class)
                .hasMessage("An invoice has already been generated for this billing snapshot.");

        verify(billingSnapshotRepository, never()).save(any());
        assertThat(snapshot.getStatus()).isNotEqualTo(BillingSnapshotStatus.INVOICED);
    }

    // Missing active company profile fails with the existing not-found error before anything is persisted.
    // (Covered in detail by generateInvoice_noActiveCompanyProfile_*.)

    // CASE 16 — Invoice generation rolls back on unexpected persistence failure.
    @Test
    void generateInvoice_persistenceFails_snapshotNotMarkedInvoiced() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class)))
                .thenThrow(new RuntimeException("unexpected database error"));

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(RuntimeException.class);

        // The @Transactional boundary ensures the in-memory INVOICED mutation, if any,
        // is rolled back at the database level; here we assert it was never even reached.
        verify(billingSnapshotRepository, never()).save(any());
        assertThat(snapshot.getStatus()).isNotEqualTo(BillingSnapshotStatus.INVOICED);
    }

    // CASE 17 — Tax calculation totals inconsistency is detected instead of silently corrected.
    @Test
    void generateInvoice_taxCalculationTotalsInconsistent_throwsValidationException() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();
        taxCalculation.setGrandTotal(new BigDecimal("9999.00")); // deliberately wrong

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Tax calculation totals are inconsistent for this billing snapshot. Invoice cannot be generated.");

        verify(invoiceRepository, never()).save(any());
    }

    // CASE 18 — GET returns the persisted invoice without recalculating anything.
    @Test
    void getInvoiceByBillingSnapshotId_existingInvoice_returnsIt() {
        Invoice invoice = Invoice.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber("INV-20260908170000")
                .billingSnapshotId(snapshotId)
                .billingSnapshotNumber("BS-20260908164549")
                .taxCalculationId(UUID.randomUUID())
                .clientId(clientId)
                .clientName("Account Management")
                .projectId(23L)
                .projectName("Website Redesign")
                .billingPeriodStart(LocalDate.of(2026, 6, 1))
                .billingPeriodEnd(LocalDate.of(2026, 8, 30))
                .currencyCode("USD")
                .paymentTermCode("NET_30")
                .subtotal(new BigDecimal("5500.00"))
                .totalTaxAmount(new BigDecimal("990.00"))
                .grandTotal(new BigDecimal("6490.00"))
                .invoiceDate(LocalDate.now())
                .generatedAt(LocalDateTime.now())
                .status(InvoiceStatus.GENERATED)
                .items(new ArrayList<>())
                .taxComponents(new ArrayList<>())
                .build();

        when(billingSnapshotRepository.existsById(snapshotId)).thenReturn(true);
        when(invoiceRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(invoice));

        InvoiceResponseDto response = invoiceService.getInvoiceByBillingSnapshotId(snapshotId);

        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(response.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");

        verifyNoInteractions(billingConfigurationService);
        verify(taxCalculationRepository, never()).findByBillingSnapshotId(any());
    }

    // CASE 19 — GET fails correctly when no invoice exists yet.
    @Test
    void getInvoiceByBillingSnapshotId_noInvoice_throwsResourceNotFoundException() {
        when(billingSnapshotRepository.existsById(snapshotId)).thenReturn(true);
        when(invoiceRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.getInvoiceByBillingSnapshotId(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("No invoice has been generated for this billing snapshot.");
    }

    // CASE 20 — GET fails correctly when the billing snapshot itself does not exist.
    @Test
    void getInvoiceByBillingSnapshotId_snapshotNotFound_throwsResourceNotFoundException() {
        when(billingSnapshotRepository.existsById(snapshotId)).thenReturn(false);

        assertThatThrownBy(() -> invoiceService.getInvoiceByBillingSnapshotId(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Billing snapshot could not be found.");
    }

    private Invoice persistedInvoice(
            String invoiceNumber,
            String snapshotNumber,
            UUID billingSnapshotId,
            String clientName,
            String projectName
    ) {
        return Invoice.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber(invoiceNumber)
                .billingSnapshotId(billingSnapshotId)
                .billingSnapshotNumber(snapshotNumber)
                .taxCalculationId(UUID.randomUUID())
                .clientId(UUID.randomUUID())
                .clientName(clientName)
                .projectId(23L)
                .projectName(projectName)
                .billingPeriodStart(LocalDate.of(2026, 6, 1))
                .billingPeriodEnd(LocalDate.of(2026, 8, 30))
                .currencyCode("USD")
                .paymentTermCode("NET_30")
                .subtotal(new BigDecimal("5500.00"))
                .totalTaxAmount(new BigDecimal("990.00"))
                .grandTotal(new BigDecimal("6490.00"))
                .invoiceDate(LocalDate.of(2026, 9, 9))
                .dueDate(LocalDate.of(2026, 10, 9))
                .generatedAt(LocalDateTime.now())
                .status(InvoiceStatus.GENERATED)
                .items(new ArrayList<>())
                .taxComponents(new ArrayList<>())
                .build();
    }

    // LIST CASE 1 — Empty invoice list.
    @Test
    void getAllInvoices_noInvoicesGenerated_returnsEmptyList() {
        when(invoiceRepository.findAllByOrderByGeneratedAtDesc()).thenReturn(List.of());

        List<InvoiceSummaryResponseDto> response = invoiceService.getAllInvoices();

        assertThat(response).isEmpty();
    }

    // LIST CASE 2 — Multiple invoices, every summary field correctly mapped from the persisted Invoice.
    @Test
    void getAllInvoices_multipleInvoices_returnsAllWithCorrectFields() {
        UUID secondSnapshotId = UUID.randomUUID();

        Invoice first = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        Invoice second = persistedInvoice(
                "INV-20260909091500", "BS-20260909080000", secondSnapshotId,
                "Globex Corp", "Data Migration");

        when(invoiceRepository.findAllByOrderByGeneratedAtDesc()).thenReturn(List.of(first, second));

        List<InvoiceSummaryResponseDto> response = invoiceService.getAllInvoices();

        assertThat(response).hasSize(2);

        InvoiceSummaryResponseDto firstDto = response.get(0);
        assertThat(firstDto.getInvoiceId()).isEqualTo(first.getInvoiceId());
        assertThat(firstDto.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(firstDto.getStatus()).isEqualTo(InvoiceStatus.GENERATED);
        assertThat(firstDto.getBillingSnapshotId()).isEqualTo(snapshotId);
        assertThat(firstDto.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");
        assertThat(firstDto.getClientName()).isEqualTo("Account Management");
        assertThat(firstDto.getProjectName()).isEqualTo("Website Redesign");
        assertThat(firstDto.getBillingPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(firstDto.getBillingPeriodEnd()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(firstDto.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(firstDto.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(firstDto.getCurrencyCode()).isEqualTo("USD");
        assertThat(firstDto.getPaymentTermCode()).isEqualTo("NET_30");
        assertThat(firstDto.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(firstDto.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(firstDto.getGrandTotal()).isEqualByComparingTo("6490.00");

        InvoiceSummaryResponseDto secondDto = response.get(1);
        assertThat(secondDto.getInvoiceNumber()).isEqualTo("INV-20260909091500");
        assertThat(secondDto.getBillingSnapshotId()).isEqualTo(secondSnapshotId);
        assertThat(secondDto.getBillingSnapshotNumber()).isEqualTo("BS-20260909080000");
        assertThat(secondDto.getClientName()).isEqualTo("Globex Corp");
        assertThat(secondDto.getProjectName()).isEqualTo("Data Migration");
    }

    // LIST CASE 3 — Listing is strictly read-only: no invoice is created and no BillingSnapshot is touched.
    @Test
    void getAllInvoices_isReadOnly_neverPersistsOrTouchesBillingSnapshot() {
        when(invoiceRepository.findAllByOrderByGeneratedAtDesc()).thenReturn(
                List.of(persistedInvoice(
                        "INV-20260908170000", "BS-20260908164549", snapshotId,
                        "Account Management", "Website Redesign"))
        );

        invoiceService.getAllInvoices();

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);
        verifyNoInteractions(billingConfigurationService);
    }

    // =====================================================================
    // Invoice preview — pre-generation, built from real snapshot items
    // =====================================================================

    private BillingSnapshot tmSnapshotWithThreeTimesheets() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setPaymentTermName("Net 30");
        snapshot.setBillingType("Time & Material");
        snapshot.getItems().clear();
        // Deliberately out of order to prove the preview sorts by work date.
        for (int day : new int[] {7, 5, 6}) {
            snapshot.getItems().add(
                    BillingSnapshotItem.builder()
                            .billingSnapshotItemId(UUID.randomUUID())
                            .billingSnapshot(snapshot)
                            .itemType(BillingItemType.TIME_ENTRY)
                            .itemName("Bolli Teja")
                            .resourceName("Bolli Teja")
                            .sourceReferenceId("TMS-" + day)
                            .quantity(new BigDecimal("11.00"))
                            .rate(new BigDecimal("800.00"))
                            .amount(new BigDecimal("8800.00"))
                            .workDate(LocalDate.of(2026, 8, day))
                            .approvalStatus("APPROVED")
                            .build());
        }
        return snapshot;
    }

    private TaxCalculation tmTaxCalculation() {
        TaxCalculation calculation = completedTaxCalculation();
        calculation.setTaxableAmount(new BigDecimal("26400.00"));
        calculation.setTotalTaxAmount(new BigDecimal("4752.00"));
        calculation.setGrandTotal(new BigDecimal("31152.00"));
        calculation.getComponents().forEach(c -> c.setTaxAmount(new BigDecimal("2376.00")));
        return calculation;
    }

    private InvoiceResponseDto stubAndPreview() {
        BillingConfigurationResponseDto configuration = configuration();
        configuration.setProjectCode("WEB-001");
        return stubAndPreview(configuration);
    }

    private InvoiceResponseDto stubAndPreview(BillingConfigurationResponseDto configuration) {
        BillingSnapshot snapshot = tmSnapshotWithThreeTimesheets();
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(invoiceRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.empty());
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId))
                .thenReturn(Optional.of(tmTaxCalculation()));
        when(billingConfigurationService.getBillingConfiguration(snapshot.getBillingConfigurationId()))
                .thenReturn(configuration);

        return invoiceService.previewInvoice(snapshotId);
    }

    // PREVIEW 1+2+3+4 — three timesheets -> three real lines, none fabricated, summing to the subtotal.
    @Test
    void previewInvoice_threeTimesheets_returnsThreeRealLinesSummingToSubtotal() {
        InvoiceResponseDto preview = stubAndPreview();

        assertThat(preview.getItems()).hasSize(3);
        assertThat(preview.getItems()).extracting(i -> i.getWorkDate().getDayOfMonth())
                .containsExactly(5, 6, 7);
        assertThat(preview.getItems()).extracting(i -> i.getLineNumber())
                .containsExactly(1, 2, 3);
        preview.getItems().forEach(item -> {
            assertThat(item.getResourceName()).isEqualTo("Bolli Teja");
            assertThat(item.getQuantity()).isEqualByComparingTo("11.00");
            assertThat(item.getRate()).isEqualByComparingTo("800.00");
            assertThat(item.getAmount()).isEqualByComparingTo("8800.00");
            assertThat(item.getSourceReferenceId()).isNotNull();
        });

        BigDecimal lineSum = preview.getItems().stream()
                .map(i -> i.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSum).isEqualByComparingTo("26400.00");
        assertThat(preview.getSubtotal()).isEqualByComparingTo(lineSum);
        // no collapsed placeholder: no qty 1, no rate == total, no period-end work date
        assertThat(preview.getItems()).noneMatch(i -> i.getQuantity().compareTo(BigDecimal.ONE) == 0);
        assertThat(preview.getItems()).noneMatch(i -> i.getRate().compareTo(new BigDecimal("26400.00")) == 0);
        assertThat(preview.getItems()).noneMatch(i -> i.getWorkDate().equals(preview.getBillingPeriodEnd()));
    }

    // PREVIEW 5+6+7+8 — client, project, payment term and seller data mapped; tax stays the persisted tax.
    @Test
    void previewInvoice_mapsClientProjectPaymentTermSellerAndPersistedTax() {
        InvoiceResponseDto preview = stubAndPreview();

        assertThat(preview.getClientName()).isEqualTo("Account Management");
        assertThat(preview.getCountryCode()).isEqualTo("+91");
        assertThat(preview.getEmail()).isEqualTo("client@example.com");
        assertThat(preview.getPhone()).isEqualTo("9876543210");
        // not stored anywhere in the system - must stay unavailable, not invented
        assertThat(preview.getBillingAddress()).isNull();
        assertThat(preview.getGstinOrTaxId()).isNull();

        assertThat(preview.getProjectName()).isEqualTo("Website Redesign");
        assertThat(preview.getProjectCode()).isEqualTo("WEB-001");
        assertThat(preview.getPaymentTermName()).isEqualTo("Net 30");
        assertThat(preview.getPaymentTermCode()).isEqualTo("NET_30");

        assertThat(preview.getSellerLegalName()).isEqualTo("Paves Technologies Pvt Ltd");
        assertThat(preview.getSellerGstin()).isEqualTo("36AAAAA0000A1Z5");

        assertThat(preview.getTotalTaxAmount()).isEqualByComparingTo("4752.00");
        assertThat(preview.getGrandTotal()).isEqualByComparingTo("31152.00");
    }

    // PREVIEW COUNTRY — the client's stored country is exposed; nothing finer-grained is invented.
    @Test
    void previewInvoice_clientCountryNameAndCode_areExposed() {
        BillingConfigurationResponseDto configuration = configuration();
        configuration.setCountryName("India");
        configuration.setCountryCode("IN");

        InvoiceResponseDto preview = stubAndPreview(configuration);

        assertThat(preview.getClientCountryName()).isEqualTo("India");
        assertThat(preview.getClientCountryCode()).isEqualTo("IN");
        // existing field kept for backward compatibility
        assertThat(preview.getCountryCode()).isEqualTo("IN");
    }

    @Test
    void previewInvoice_clientWithoutCountry_exposesNullNotAnInventedLocation() {
        BillingConfigurationResponseDto configuration = configuration();
        configuration.setCountryName(null);
        configuration.setCountryCode(null);

        InvoiceResponseDto preview = stubAndPreview(configuration);

        assertThat(preview.getClientCountryName()).isNull();
        assertThat(preview.getClientCountryCode()).isNull();
        assertThat(preview.getBillingAddress()).isNull();
    }

    // The seller is in Hyderabad; that must never leak into any client location field.
    @Test
    void previewInvoice_sellerHyderabad_isNotClientLocation() {
        BillingConfigurationResponseDto configuration = configuration();
        configuration.setCountryName("India");
        configuration.setCountryCode("IN");

        InvoiceResponseDto preview = stubAndPreview(configuration);

        assertThat(preview.getSellerCity()).isEqualTo("Hyderabad");
        assertThat(preview.getClientCountryName()).isEqualTo("India");
        assertThat(preview.getClientCountryCode()).isEqualTo("IN");
        assertThat(preview.getBillingAddress()).isNull();
        assertThat(preview.getClientCountryName()).doesNotContain("Hyderabad");
        // tax-calculation data is untouched and independent of the display country
        assertThat(preview.getTaxRegionCode()).isEqualTo(tmSnapshotWithThreeTimesheets().getTaxRegionCode());
        assertThat(preview.getDestinationTaxJurisdictionCode())
                .isEqualTo(tmSnapshotWithThreeTimesheets().getDestinationTaxJurisdictionCode());
    }

    // PREVIEW 9 — pre-generation fields are unavailable, not fabricated, and the response says so.
    @Test
    void previewInvoice_preGeneration_leavesInvoiceFieldsNullAndPersistsNothing() {
        InvoiceResponseDto preview = stubAndPreview();

        assertThat(preview.getGenerated()).isFalse();
        assertThat(preview.getInvoiceId()).isNull();
        assertThat(preview.getInvoiceNumber()).isNull();
        assertThat(preview.getStatus()).isNull();
        assertThat(preview.getInvoiceDate()).isNull();
        assertThat(preview.getDueDate()).isNull();
        assertThat(preview.getGeneratedAt()).isNull();

        verify(invoiceRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).save(any());
    }

    // PREVIEW 10 — an existing invoice is returned as generated, with numbered lines.
    @Test
    void previewInvoice_existingInvoice_returnsGeneratedInvoice() {
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.getItems().add(InvoiceItem.builder()
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("Bolli Teja")
                .quantity(new BigDecimal("11.00"))
                .rate(new BigDecimal("800.00"))
                .amount(new BigDecimal("8800.00"))
                .build());
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(taxCompletedSnapshot()));
        when(invoiceRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(invoice));

        InvoiceResponseDto response = invoiceService.previewInvoice(snapshotId);

        assertThat(response.getGenerated()).isTrue();
        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.GENERATED);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getLineNumber()).isEqualTo(1);
        verifyNoInteractions(taxCalculationRepository);
    }

    // PREVIEW — a snapshot that has not completed tax calculation has no preview.
    @Test
    void previewInvoice_snapshotNotTaxCompleted_isRejected() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.READY_FOR_TAX);
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(invoiceRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.previewInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class);
    }

    // GENERATION — one invoice line per snapshot item, amounts copied unchanged (existing behavior).
    @Test
    void generateInvoice_threeTimesheets_createsThreeLinesCopiedFromSnapshotItems() {
        BillingSnapshot snapshot = tmSnapshotWithThreeTimesheets();
        TaxCalculation taxCalculation = tmTaxCalculation();
        stubSnapshotInvoiceGeneration(snapshot, taxCalculation);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getGenerated()).isTrue();
        assertThat(response.getItems()).hasSize(3);
        assertThat(response.getItems()).extracting(i -> i.getLineNumber()).containsExactly(1, 2, 3);
        assertThat(response.getItems().stream().map(i -> i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("26400.00");
        assertThat(response.getSubtotal()).isEqualByComparingTo("26400.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("4752.00");
    }

    // =====================================================================
    // Invoice Generation workspace — TAX_COMPLETED candidates + invoices
    // =====================================================================

    private com.AccountReceivableManagement.dto.invoice_generation.InvoiceGenerationWorkspaceResponseDto
    workspaceWithCandidate(List<Invoice> invoices) {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setBillingType("Time & Material");

        when(invoiceRepository.findAllByOrderByGeneratedAtDesc()).thenReturn(invoices);
        when(billingSnapshotRepository.findAllById(any())).thenReturn(List.of());
        when(billingSnapshotRepository.findAllByStatusOrderByCreatedDateDesc(BillingSnapshotStatus.TAX_COMPLETED))
                .thenReturn(List.of(snapshot));
        lenient().when(taxCalculationRepository.findByBillingSnapshotId(snapshotId))
                .thenReturn(Optional.of(completedTaxCalculation()));
        lenient().when(billingConfigurationService.getBillingConfiguration(any()))
                .thenReturn(configuration());

        return invoiceService.getInvoiceGenerationWorkspace();
    }

    // WORKSPACE CASE 1+2 — TAX_COMPLETED snapshot with no invoice is a READY_FOR_INVOICE candidate.
    @Test
    void getInvoiceGenerationWorkspace_taxCompletedWithoutInvoice_isReadyForInvoice() {
        var response = workspaceWithCandidate(List.of());

        assertThat(response.getRows()).hasSize(1);
        var row = response.getRows().get(0);
        assertThat(row.getWorkspaceStatus()).isEqualTo(
                com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceWorkspaceStatus.READY_FOR_INVOICE);
        assertThat(row.getSnapshotId()).isEqualTo(snapshotId);
        assertThat(row.getSnapshotStatus()).isEqualTo(BillingSnapshotStatus.TAX_COMPLETED);
        assertThat(row.getBillingType()).isEqualTo("Time & Material");
        assertThat(row.getCurrencyCode()).isEqualTo("USD");
        assertThat(row.getAmount()).isEqualByComparingTo("5500.00");
        assertThat(row.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(row.getGrandTotal()).isEqualByComparingTo("6490.00");
        assertThat(row.getInvoiceId()).isNull();
        assertThat(row.getInvoiceNumber()).isNull();
    }

    // WORKSPACE CASE 8 — a candidate is never counted as invoiced.
    @Test
    void getInvoiceGenerationWorkspace_candidateIsNotCountedAsInvoiced() {
        var summary = workspaceWithCandidate(List.of()).getSummary();

        assertThat(summary.getReadyForInvoiceCount()).isEqualTo(1);
        assertThat(summary.getReadyForInvoiceAmount()).isEqualByComparingTo("6490.00");
        assertThat(summary.getGeneratedCount()).isZero();
        assertThat(summary.getInvoicedCount()).isZero();
        assertThat(summary.getTotalInvoicedAmount()).isEqualByComparingTo("0");
    }

    // WORKSPACE CASE 3 — TAX_COMPLETED snapshot that already has an invoice returns the invoice, not a candidate.
    @Test
    void getInvoiceGenerationWorkspace_taxCompletedWithExistingInvoice_returnsInvoiceNotCandidate() {
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");

        var response = workspaceWithCandidate(List.of(invoice));

        assertThat(response.getRows()).hasSize(1);
        var row = response.getRows().get(0);
        assertThat(row.getInvoiceId()).isEqualTo(invoice.getInvoiceId());
        assertThat(row.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(response.getSummary().getReadyForInvoiceCount()).isZero();
    }

    // WORKSPACE CASES 4-7 — GENERATED / PENDING_APPROVAL / APPROVED / REJECTED invoices map 1:1; INVOICED snapshot status carried.
    @Test
    void getInvoiceGenerationWorkspace_existingInvoices_mapStatusAndCountKpis() {
        Invoice generated = persistedInvoice("INV-1", "BS-1", UUID.randomUUID(), "A", "P1");
        Invoice pending = persistedInvoice("INV-2", "BS-2", UUID.randomUUID(), "A", "P2");
        pending.setStatus(InvoiceStatus.PENDING_APPROVAL);
        Invoice approved = persistedInvoice("INV-3", "BS-3", UUID.randomUUID(), "A", "P3");
        approved.setStatus(InvoiceStatus.APPROVED);
        Invoice rejected = persistedInvoice("INV-4", "BS-4", UUID.randomUUID(), "A", "P4");
        rejected.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot invoicedSnapshot = taxCompletedSnapshot();
        invoicedSnapshot.setId(generated.getBillingSnapshotId());
        invoicedSnapshot.setStatus(BillingSnapshotStatus.INVOICED);

        when(invoiceRepository.findAllByOrderByGeneratedAtDesc())
                .thenReturn(List.of(generated, pending, approved, rejected));
        when(billingSnapshotRepository.findAllById(any())).thenReturn(List.of(invoicedSnapshot));
        when(billingSnapshotRepository.findAllByStatusOrderByCreatedDateDesc(BillingSnapshotStatus.TAX_COMPLETED))
                .thenReturn(List.of());

        var response = invoiceService.getInvoiceGenerationWorkspace();

        assertThat(response.getRows()).extracting(r -> r.getWorkspaceStatus().name())
                .containsExactly("GENERATED", "PENDING_APPROVAL", "APPROVED", "REJECTED");
        assertThat(response.getRows().get(0).getSnapshotStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        assertThat(response.getRows().get(0).getInvoiceStatus()).isEqualTo(InvoiceStatus.GENERATED);

        var summary = response.getSummary();
        assertThat(summary.getReadyForInvoiceCount()).isZero();
        assertThat(summary.getGeneratedCount()).isEqualTo(1);
        assertThat(summary.getPendingApprovalCount()).isEqualTo(1);
        assertThat(summary.getApprovedCount()).isEqualTo(1);
        assertThat(summary.getRejectedCount()).isEqualTo(1);
        assertThat(summary.getInvoicedCount()).isEqualTo(4);
        // rejected invoice excluded from the invoiced amount
        assertThat(summary.getTotalInvoicedAmount()).isEqualByComparingTo("19470.00");
    }

    // WORKSPACE — strictly read-only: nothing is saved and no snapshot status changes.
    @Test
    void getInvoiceGenerationWorkspace_isReadOnly() {
        workspaceWithCandidate(List.of());

        verify(invoiceRepository, never()).save(any());
        verify(billingSnapshotRepository, never()).save(any());
        verify(companyProfileService, never()).getActive();
    }

    // WORKSPACE — schedule-based (e.g. Recurring) TAX_CALCULATED occurrences have no snapshot and are candidates too.
    private void stubWorkspace(List<Invoice> invoices, List<BillingSnapshot> taxCompletedSnapshots,
                               List<BillingSchedule> taxCalculatedSchedules) {
        when(invoiceRepository.findAllByOrderByGeneratedAtDesc()).thenReturn(invoices);
        when(billingSnapshotRepository.findAllById(any())).thenReturn(List.of());
        when(billingSnapshotRepository.findAllByStatusOrderByCreatedDateDesc(BillingSnapshotStatus.TAX_COMPLETED))
                .thenReturn(taxCompletedSnapshots);
        when(billingScheduleRepository.findByPeriodStatusAndTaxStatusAndIsActiveTrue(
                BillingPeriodStatus.TAX_CALCULATED, BillingPeriodStatus.TAX_CALCULATED))
                .thenReturn(taxCalculatedSchedules);
        lenient().when(taxCalculationRepository.findByBillingSnapshotId(snapshotId))
                .thenReturn(Optional.of(completedTaxCalculation()));
        lenient().when(billingSnapshotRepository
                .findByBillingConfigurationIdAndBillingPeriodStartAndBillingPeriodEnd(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private BillingSchedule recurringScheduleCandidate(String billingTypeName) {
        BillingSchedule schedule = taxCalculatedSchedule();
        lenient().when(taxCalculationRepository.findByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(completedScheduleTaxCalculation(schedule.getBillingScheduleId())));
        lenient().when(billingConfigurationService.getBillingConfiguration(
                schedule.getBillingConfiguration().getBillingConfigurationId()))
                .thenReturn(BillingConfigurationResponseDto.builder()
                        .clientName("Aditya Teja").projectName("Card Integration")
                        .billingTypeName(billingTypeName).currencyCode("USD").build());
        return schedule;
    }

    @Test
    void getInvoiceGenerationWorkspace_tmSnapshotAndRecurringSchedule_bothReadyForInvoice() {
        BillingSnapshot tm = taxCompletedSnapshot();
        tm.setBillingType("Time & Material");
        lenient().when(billingConfigurationService.getBillingConfiguration(any()))
                .thenReturn(configuration());
        BillingSchedule recurring = recurringScheduleCandidate("Recurring");
        stubWorkspace(List.of(), List.of(tm), List.of(recurring));

        var response = invoiceService.getInvoiceGenerationWorkspace();

        assertThat(response.getRows()).hasSize(2);
        assertThat(response.getRows()).extracting(r -> r.getWorkspaceStatus().name())
                .containsOnly("READY_FOR_INVOICE");
        assertThat(response.getRows()).extracting(r -> r.getBillingType())
                .containsExactlyInAnyOrder("Time & Material", "Recurring");
        var recurringRow = response.getRows().stream()
                .filter(r -> r.getBillingScheduleId() != null).findFirst().orElseThrow();
        assertThat(recurringRow.getBillingScheduleId()).isEqualTo(recurring.getBillingScheduleId());
        assertThat(recurringRow.getClientName()).isEqualTo("Aditya Teja");
        assertThat(recurringRow.getGrandTotal()).isEqualByComparingTo("2950.00");
        assertThat(response.getSummary().getReadyForInvoiceCount()).isEqualTo(2);
        assertThat(response.getSummary().getReadyForInvoiceAmount()).isEqualByComparingTo("9440.00");
        assertThat(response.getSummary().getInvoicedCount()).isZero();
    }

    @Test
    void getInvoiceGenerationWorkspace_scheduleBillingTypeDoesNotAffectEligibility() {
        List<BillingSchedule> schedules = List.of(
                recurringScheduleCandidate("Recurring"),
                recurringScheduleCandidate("Fixed Price / Milestone"),
                recurringScheduleCandidate("Retainer / Subscription"));
        stubWorkspace(List.of(), List.of(), schedules);

        var response = invoiceService.getInvoiceGenerationWorkspace();

        assertThat(response.getRows()).hasSize(3);
        assertThat(response.getSummary().getReadyForInvoiceCount()).isEqualTo(3);
    }

    @Test
    void getInvoiceGenerationWorkspace_scheduleWithExistingInvoice_isNotDuplicatedAsCandidate() {
        BillingSchedule schedule = recurringScheduleCandidate("Recurring");
        Invoice invoice = persistedInvoice("INV-9", null, null, "Aditya Teja", "Card Integration");
        invoice.setBillingScheduleId(schedule.getBillingScheduleId());
        stubWorkspace(List.of(invoice), List.of(), List.of(schedule));

        var response = invoiceService.getInvoiceGenerationWorkspace();

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getInvoiceId()).isEqualTo(invoice.getInvoiceId());
        assertThat(response.getSummary().getReadyForInvoiceCount()).isZero();
    }

    @Test
    void getInvoiceGenerationWorkspace_scheduleAlreadyFlaggedInvoiced_isNotACandidate() {
        BillingSchedule schedule = recurringScheduleCandidate("Recurring");
        schedule.setIsInvoiced(true);
        stubWorkspace(List.of(), List.of(), List.of(schedule));

        assertThat(invoiceService.getInvoiceGenerationWorkspace().getRows()).isEmpty();
    }

    @Test
    void getInvoiceGenerationWorkspace_scheduleCoveredBySnapshot_isNotDuplicated() {
        BillingSnapshot tm = taxCompletedSnapshot();
        BillingSchedule schedule = recurringScheduleCandidate("Time & Material");
        stubWorkspace(List.of(), List.of(tm), List.of(schedule));
        when(billingSnapshotRepository
                .findByBillingConfigurationIdAndBillingPeriodStartAndBillingPeriodEnd(
                        schedule.getBillingConfiguration().getBillingConfigurationId(),
                        schedule.getPeriodStartDate(), schedule.getPeriodEndDate()))
                .thenReturn(Optional.of(tm));

        var response = invoiceService.getInvoiceGenerationWorkspace();

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getSummary().getReadyForInvoiceCount()).isEqualTo(1);
    }

    // Only completed-tax stages are queried; READY_FOR_TAX / UPCOMING / PENDING are never requested.
    @Test
    void getInvoiceGenerationWorkspace_queriesOnlyTaxCompletedStages() {
        stubWorkspace(List.of(), List.of(), List.of());

        invoiceService.getInvoiceGenerationWorkspace();

        verify(billingSnapshotRepository).findAllByStatusOrderByCreatedDateDesc(BillingSnapshotStatus.TAX_COMPLETED);
        verify(billingSnapshotRepository, never())
                .findAllByStatusOrderByCreatedDateDesc(BillingSnapshotStatus.READY_FOR_TAX);
        verify(billingScheduleRepository).findByPeriodStatusAndTaxStatusAndIsActiveTrue(
                BillingPeriodStatus.TAX_CALCULATED, BillingPeriodStatus.TAX_CALCULATED);
        verify(billingScheduleRepository, never()).findByPeriodStatusAndTaxStatusAndIsActiveTrue(
                BillingPeriodStatus.TAX_PENDING, BillingPeriodStatus.TAX_PENDING);
    }

    // =====================================================================
    // Invoice Approval — Phase 1: GENERATED -> PENDING_APPROVAL -> APPROVED
    // =====================================================================

    // SUBMIT CASE 1 — GENERATED invoice submits successfully.
    @Test
    void submitForApproval_generatedInvoice_transitionsToPendingApprovalAndRecordsHistory() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.GENERATED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceResponseDto response = invoiceService.submitForApproval(invoiceId);

        // Status transition.
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        // Financial values, identity, and every other field remain exactly as generated.
        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");
        assertThat(response.getBillingSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");

        // BillingSnapshot / TaxCalculation are never touched by this operation.
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);

        // No new invoice is generated - exactly one save, of the same instance.
        verify(invoiceRepository, times(1)).save(invoice);

        // Approval history recorded.
        ArgumentCaptor<InvoiceApprovalHistory> historyCaptor = ArgumentCaptor.forClass(InvoiceApprovalHistory.class);
        verify(invoiceApprovalHistoryRepository).save(historyCaptor.capture());
        InvoiceApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(history.getPreviousStatus()).isEqualTo(InvoiceStatus.GENERATED);
        assertThat(history.getNewStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(history.getAction()).isEqualTo(InvoiceApprovalAction.SUBMITTED);
        assertThat(history.getActionBy()).isEqualTo("SYSTEM");
        assertThat(history.getActionAt()).isNotNull();
    }

    // SUBMIT CASE 2 — Invoice not found.
    @Test
    void submitForApproval_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.submitForApproval(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // SUBMIT CASE 3/4 — Only GENERATED and REJECTED can be submitted.
    @Test
    void submitForApproval_pendingApprovalInvoice_throwsValidationException() {
        assertSubmitRejected(InvoiceStatus.PENDING_APPROVAL);
    }

    @Test
    void submitForApproval_approvedInvoice_throwsValidationException() {
        assertSubmitRejected(InvoiceStatus.APPROVED);
    }

    private void assertSubmitRejected(InvoiceStatus currentStatus) {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(currentStatus);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.submitForApproval(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only invoices with status GENERATED or REJECTED can be submitted for approval.");

        assertThat(invoice.getStatus()).isEqualTo(currentStatus);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // =====================================================================
    // Invoice Rejection — Phase 2: PENDING_APPROVAL -> REJECTED -> (correction)
    // -> PENDING_APPROVAL (resubmission) -> APPROVED
    // =====================================================================

    // SUBMIT CASE 5 — A REJECTED invoice can be resubmitted for approval after correction.
    @Test
    void submitForApproval_rejectedInvoice_transitionsToPendingApprovalAndRecordsNewSubmittedHistory() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        // A correction refresh (CORRECTED) already occurred after the latest REJECTED
        // entry, so Phase 2B's resubmission guard is satisfied.
        LocalDateTime rejectedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        LocalDateTime correctedAt = LocalDateTime.of(2026, 9, 9, 10, 30);
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, rejectedAt),
                        historyEntry(invoiceId, InvoiceStatus.REJECTED, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.CORRECTED, correctedAt)
                ));

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceResponseDto response = invoiceService.submitForApproval(invoiceId);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        // Financial fields remain untouched - no invoice-amount editing occurs on resubmission.
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);

        ArgumentCaptor<InvoiceApprovalHistory> historyCaptor = ArgumentCaptor.forClass(InvoiceApprovalHistory.class);
        verify(invoiceApprovalHistoryRepository).save(historyCaptor.capture());
        InvoiceApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(history.getAction()).isEqualTo(InvoiceApprovalAction.SUBMITTED);
        assertThat(history.getPreviousStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(history.getNewStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(history.getActionBy()).isEqualTo("SYSTEM");
    }

    // REJECT CASE 1 — PENDING_APPROVAL invoice rejects successfully and records history.
    @Test
    void rejectInvoice_pendingApprovalInvoice_transitionsToRejectedAndRecordsHistory() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.PENDING_APPROVAL);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceRejectionRequestDto request = InvoiceRejectionRequestDto.builder()
                .reason("Billing hours are incorrect for the selected period.")
                .build();

        InvoiceResponseDto response = invoiceService.rejectInvoice(invoiceId, request);

        // Status transition.
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        // Financial values are never touched by rejection.
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);

        verify(invoiceRepository, times(1)).save(invoice);

        // Approval history recorded with the exact rejection reason.
        ArgumentCaptor<InvoiceApprovalHistory> historyCaptor = ArgumentCaptor.forClass(InvoiceApprovalHistory.class);
        verify(invoiceApprovalHistoryRepository).save(historyCaptor.capture());
        InvoiceApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(history.getPreviousStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(history.getNewStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(history.getAction()).isEqualTo(InvoiceApprovalAction.REJECTED);
        assertThat(history.getActionBy()).isEqualTo("SYSTEM");
        assertThat(history.getActionAt()).isNotNull();
        assertThat(history.getComment())
                .isEqualTo("Billing hours are incorrect for the selected period.");
    }

    // REJECT CASE 2 — Null rejection reason is rejected.
    @Test
    void rejectInvoice_nullReason_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceRejectionRequestDto request = InvoiceRejectionRequestDto.builder()
                .reason(null)
                .build();

        assertThatThrownBy(() -> invoiceService.rejectInvoice(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Rejection reason is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REJECT CASE 3 — Blank rejection reason is rejected.
    @Test
    void rejectInvoice_blankReason_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceRejectionRequestDto request = InvoiceRejectionRequestDto.builder()
                .reason("")
                .build();

        assertThatThrownBy(() -> invoiceService.rejectInvoice(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Rejection reason is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REJECT CASE 4 — Whitespace-only rejection reason is rejected.
    @Test
    void rejectInvoice_whitespaceOnlyReason_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceRejectionRequestDto request = InvoiceRejectionRequestDto.builder()
                .reason("   ")
                .build();

        assertThatThrownBy(() -> invoiceService.rejectInvoice(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Rejection reason is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REJECT CASE 5 — Invoice not found.
    @Test
    void rejectInvoice_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        InvoiceRejectionRequestDto request = InvoiceRejectionRequestDto.builder()
                .reason("Incorrect billing hours.")
                .build();

        assertThatThrownBy(() -> invoiceService.rejectInvoice(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REJECT CASE 6/7/8 — Only PENDING_APPROVAL can be rejected.
    @Test
    void rejectInvoice_generatedInvoice_throwsValidationException() {
        assertRejectRejected(InvoiceStatus.GENERATED);
    }

    @Test
    void rejectInvoice_approvedInvoice_throwsValidationException() {
        assertRejectRejected(InvoiceStatus.APPROVED);
    }

    @Test
    void rejectInvoice_alreadyRejectedInvoice_throwsValidationException() {
        assertRejectRejected(InvoiceStatus.REJECTED);
    }

    private void assertRejectRejected(InvoiceStatus currentStatus) {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(currentStatus);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        InvoiceRejectionRequestDto request = InvoiceRejectionRequestDto.builder()
                .reason("Incorrect billing hours.")
                .build();

        assertThatThrownBy(() -> invoiceService.rejectInvoice(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only invoices pending approval can be rejected.");

        assertThat(invoice.getStatus()).isEqualTo(currentStatus);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // FULL CYCLE — submit -> reject -> resubmit -> approve, preserving every history entry
    // in chronological order (as required by the approval-history endpoint).
    @Test
    void fullRejectionAndResubmissionCycle_preservesEveryHistoryEntryInOrder() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.GENERATED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // GENERATED -> PENDING_APPROVAL
        invoiceService.submitForApproval(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        // PENDING_APPROVAL -> REJECTED
        invoiceService.rejectInvoice(invoiceId, InvoiceRejectionRequestDto.builder()
                .reason("Incorrect billing hours.")
                .build());
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        // REJECTED -> PENDING_APPROVAL (resubmission after correction)
        invoiceService.submitForApproval(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        // PENDING_APPROVAL -> APPROVED
        invoiceService.approveInvoice(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.APPROVED);

        ArgumentCaptor<InvoiceApprovalHistory> historyCaptor = ArgumentCaptor.forClass(InvoiceApprovalHistory.class);
        verify(invoiceApprovalHistoryRepository, times(4)).save(historyCaptor.capture());
        List<InvoiceApprovalHistory> entries = historyCaptor.getAllValues();

        assertThat(entries).hasSize(4);

        assertThat(entries.get(0).getAction()).isEqualTo(InvoiceApprovalAction.SUBMITTED);
        assertThat(entries.get(0).getPreviousStatus()).isEqualTo(InvoiceStatus.GENERATED);
        assertThat(entries.get(0).getNewStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        assertThat(entries.get(1).getAction()).isEqualTo(InvoiceApprovalAction.REJECTED);
        assertThat(entries.get(1).getPreviousStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(entries.get(1).getNewStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(entries.get(1).getComment()).isEqualTo("Incorrect billing hours.");

        assertThat(entries.get(2).getAction()).isEqualTo(InvoiceApprovalAction.SUBMITTED);
        assertThat(entries.get(2).getPreviousStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(entries.get(2).getNewStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        assertThat(entries.get(3).getAction()).isEqualTo(InvoiceApprovalAction.APPROVED);
        assertThat(entries.get(3).getPreviousStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(entries.get(3).getNewStatus()).isEqualTo(InvoiceStatus.APPROVED);

        // The original REJECTED history entry is untouched by the later resubmission/approval.
        assertThat(entries.get(1).getComment()).isEqualTo("Incorrect billing hours.");
    }

    // APPROVE CASE 1 — PENDING_APPROVAL invoice approves successfully.
    @Test
    void approveInvoice_pendingApprovalInvoice_transitionsToApprovedAndRecordsHistory() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.PENDING_APPROVAL);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceResponseDto response = invoiceService.approveInvoice(invoiceId);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.APPROVED);

        // Financial values unchanged - no recalculation.
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");

        // BillingSnapshot stays INVOICED - not modified by approval.
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);

        // No new invoice is created.
        verify(invoiceRepository, times(1)).save(invoice);

        ArgumentCaptor<InvoiceApprovalHistory> historyCaptor = ArgumentCaptor.forClass(InvoiceApprovalHistory.class);
        verify(invoiceApprovalHistoryRepository).save(historyCaptor.capture());
        InvoiceApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(history.getPreviousStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(history.getNewStatus()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(history.getAction()).isEqualTo(InvoiceApprovalAction.APPROVED);
        assertThat(history.getActionBy()).isEqualTo("SYSTEM");
    }

    // APPROVE CASE 2 — Invoice not found.
    @Test
    void approveInvoice_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.approveInvoice(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // APPROVE CASE 3/4/5 — Only PENDING_APPROVAL can be approved.
    @Test
    void approveInvoice_generatedInvoice_throwsValidationException() {
        assertApproveRejected(InvoiceStatus.GENERATED);
    }

    @Test
    void approveInvoice_alreadyApprovedInvoice_throwsValidationException() {
        assertApproveRejected(InvoiceStatus.APPROVED);
    }

    @Test
    void approveInvoice_rejectedInvoice_throwsValidationException() {
        assertApproveRejected(InvoiceStatus.REJECTED);
    }

    private void assertApproveRejected(InvoiceStatus currentStatus) {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(currentStatus);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.approveInvoice(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only invoices pending approval can be approved.");

        assertThat(invoice.getStatus()).isEqualTo(currentStatus);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // HISTORY CASE 1 — History returned in chronological order.
    @Test
    void getApprovalHistory_multipleEntries_returnsInChronologicalOrder() {
        UUID invoiceId = UUID.randomUUID();
        LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 9, 9, 11, 0);

        InvoiceApprovalHistory submitted = InvoiceApprovalHistory.builder()
                .invoiceApprovalHistoryId(UUID.randomUUID())
                .invoiceId(invoiceId)
                .previousStatus(InvoiceStatus.GENERATED)
                .newStatus(InvoiceStatus.PENDING_APPROVAL)
                .action(InvoiceApprovalAction.SUBMITTED)
                .actionBy("SYSTEM")
                .actionAt(submittedAt)
                .build();

        InvoiceApprovalHistory approved = InvoiceApprovalHistory.builder()
                .invoiceApprovalHistoryId(UUID.randomUUID())
                .invoiceId(invoiceId)
                .previousStatus(InvoiceStatus.PENDING_APPROVAL)
                .newStatus(InvoiceStatus.APPROVED)
                .action(InvoiceApprovalAction.APPROVED)
                .actionBy("SYSTEM")
                .actionAt(approvedAt)
                .build();

        when(invoiceRepository.existsById(invoiceId)).thenReturn(true);
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(submitted, approved));

        List<InvoiceApprovalHistoryResponseDto> history = invoiceService.getApprovalHistory(invoiceId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getAction()).isEqualTo(InvoiceApprovalAction.SUBMITTED);
        assertThat(history.get(0).getPreviousStatus()).isEqualTo(InvoiceStatus.GENERATED);
        assertThat(history.get(0).getNewStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(history.get(0).getActionAt()).isEqualTo(submittedAt);
        assertThat(history.get(1).getAction()).isEqualTo(InvoiceApprovalAction.APPROVED);
        assertThat(history.get(1).getPreviousStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(history.get(1).getNewStatus()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(history.get(1).getActionAt()).isEqualTo(approvedAt);
    }

    // HISTORY CASE 2 — Unknown invoice fails correctly.
    @Test
    void getApprovalHistory_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.existsById(invoiceId)).thenReturn(false);

        assertThatThrownBy(() -> invoiceService.getApprovalHistory(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");
    }

    // PENDING APPROVAL LIST CASE 1 — Only PENDING_APPROVAL invoices are returned.
    @Test
    void getPendingApprovalInvoices_returnsOnlyPendingApprovalInvoices() {
        Invoice pending = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        pending.setInvoiceId(UUID.randomUUID());
        pending.setStatus(InvoiceStatus.PENDING_APPROVAL);

        LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        InvoiceApprovalHistory submission = InvoiceApprovalHistory.builder()
                .invoiceApprovalHistoryId(UUID.randomUUID())
                .invoiceId(pending.getInvoiceId())
                .previousStatus(InvoiceStatus.GENERATED)
                .newStatus(InvoiceStatus.PENDING_APPROVAL)
                .action(InvoiceApprovalAction.SUBMITTED)
                .actionBy("SYSTEM")
                .actionAt(submittedAt)
                .build();

        when(invoiceRepository.findAllByStatusOrderByGeneratedAtDesc(InvoiceStatus.PENDING_APPROVAL))
                .thenReturn(List.of(pending));
        when(invoiceApprovalHistoryRepository.findTopByInvoiceIdAndActionOrderByActionAtDesc(
                pending.getInvoiceId(), InvoiceApprovalAction.SUBMITTED))
                .thenReturn(Optional.of(submission));

        List<InvoiceApprovalSummaryResponseDto> response = invoiceService.getPendingApprovalInvoices();

        assertThat(response).hasSize(1);
        InvoiceApprovalSummaryResponseDto dto = response.get(0);
        assertThat(dto.getInvoiceId()).isEqualTo(pending.getInvoiceId());
        assertThat(dto.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(dto.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(dto.getBillingSnapshotId()).isEqualTo(snapshotId);
        assertThat(dto.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");
        assertThat(dto.getClientName()).isEqualTo("Account Management");
        assertThat(dto.getProjectName()).isEqualTo("Website Redesign");
        assertThat(dto.getGrandTotal()).isEqualByComparingTo("6490.00");
        assertThat(dto.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(dto.getSubmittedBy()).isEqualTo("SYSTEM");

        // Only the PENDING_APPROVAL filter is queried - never all invoices.
        verify(invoiceRepository, never()).findAllByOrderByGeneratedAtDesc();
    }

    // PENDING APPROVAL LIST CASE 2 — Empty when nothing is pending; repository-level filter, not client-side.
    @Test
    void getPendingApprovalInvoices_noneWaiting_returnsEmptyList() {
        when(invoiceRepository.findAllByStatusOrderByGeneratedAtDesc(InvoiceStatus.PENDING_APPROVAL))
                .thenReturn(List.of());

        List<InvoiceApprovalSummaryResponseDto> response = invoiceService.getPendingApprovalInvoices();

        assertThat(response).isEmpty();
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // =====================================================================
    // Invoice Approval Dashboard — GET /api/v1/invoices/approval-workspace
    // =====================================================================

    private InvoiceApprovalHistory historyEntry(
            UUID invoiceId,
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

    // WORKSPACE CASE 1 — A never-submitted GENERATED invoice is excluded (repository-level, via EXISTS).
    @Test
    void getApprovalWorkspaceInvoices_generatedInvoiceNeverSubmitted_isExcludedByRepositoryQuery() {
        // The EXISTS-subquery repository method itself decides inclusion; a GENERATED
        // invoice that was never submitted simply never appears in what it returns.
        when(invoiceRepository.findAllInApprovalWorkflowOrderByGeneratedAtDesc())
                .thenReturn(List.of());

        List<InvoiceApprovalWorkspaceResponseDto> response = invoiceService.getApprovalWorkspaceInvoices();

        assertThat(response).isEmpty();
        // No history bulk-fetch is even attempted when there is nothing to enrich.
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // WORKSPACE CASE 2/3/4 — PENDING_APPROVAL, APPROVED, and REJECTED invoices are all included.
    @Test
    void getApprovalWorkspaceInvoices_pendingApprovedAndRejectedInvoices_areAllIncluded() {
        Invoice pending = persistedInvoice(
                "INV-P", "BS-P", UUID.randomUUID(), "Client Pending", "Project Pending");
        pending.setInvoiceId(UUID.randomUUID());
        pending.setStatus(InvoiceStatus.PENDING_APPROVAL);

        Invoice approved = persistedInvoice(
                "INV-A", "BS-A", UUID.randomUUID(), "Client Approved", "Project Approved");
        approved.setInvoiceId(UUID.randomUUID());
        approved.setStatus(InvoiceStatus.APPROVED);

        Invoice rejected = persistedInvoice(
                "INV-R", "BS-R", UUID.randomUUID(), "Client Rejected", "Project Rejected");
        rejected.setInvoiceId(UUID.randomUUID());
        rejected.setStatus(InvoiceStatus.REJECTED);

        when(invoiceRepository.findAllInApprovalWorkflowOrderByGeneratedAtDesc())
                .thenReturn(List.of(pending, approved, rejected));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdInOrderByActionAtAsc(any()))
                .thenReturn(List.of());

        List<InvoiceApprovalWorkspaceResponseDto> response = invoiceService.getApprovalWorkspaceInvoices();

        assertThat(response).hasSize(3);
        assertThat(response)
                .extracting(InvoiceApprovalWorkspaceResponseDto::getStatus)
                .containsExactlyInAnyOrder(
                        InvoiceStatus.PENDING_APPROVAL,
                        InvoiceStatus.APPROVED,
                        InvoiceStatus.REJECTED
                );
    }

    // WORKSPACE CASE 5/6/7 — submittedAt/submittedBy come from the latest SUBMITTED entry;
    // lastAction/lastActionAt come from the latest entry overall (an APPROVED invoice here).
    @Test
    void getApprovalWorkspaceInvoices_approvedInvoice_resolvesSubmittedAndLastActionCorrectly() {
        UUID invoiceId = UUID.randomUUID();
        Invoice approved = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        approved.setInvoiceId(invoiceId);
        approved.setStatus(InvoiceStatus.APPROVED);

        LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 9, 9, 11, 0);

        InvoiceApprovalHistory submitted = historyEntry(
                invoiceId, InvoiceStatus.GENERATED, InvoiceStatus.PENDING_APPROVAL,
                InvoiceApprovalAction.SUBMITTED, submittedAt);
        InvoiceApprovalHistory approvedEntry = historyEntry(
                invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.APPROVED,
                InvoiceApprovalAction.APPROVED, approvedAt);

        when(invoiceRepository.findAllInApprovalWorkflowOrderByGeneratedAtDesc())
                .thenReturn(List.of(approved));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdInOrderByActionAtAsc(List.of(invoiceId)))
                .thenReturn(List.of(submitted, approvedEntry));

        List<InvoiceApprovalWorkspaceResponseDto> response = invoiceService.getApprovalWorkspaceInvoices();

        assertThat(response).hasSize(1);
        InvoiceApprovalWorkspaceResponseDto dto = response.get(0);

        // submittedAt/submittedBy from the SUBMITTED entry specifically...
        assertThat(dto.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(dto.getSubmittedBy()).isEqualTo("SYSTEM");

        // ...while lastAction/lastActionAt reflect the most recent entry overall (APPROVED), not SUBMITTED.
        assertThat(dto.getLastAction()).isEqualTo(InvoiceApprovalAction.APPROVED);
        assertThat(dto.getLastActionAt()).isEqualTo(approvedAt);

        // WORKSPACE CASE 8 — grand total comes directly from the persisted Invoice.
        assertThat(dto.getGrandTotal()).isEqualByComparingTo("6490.00");
        assertThat(dto.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(dto.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(dto.getBillingSnapshotId()).isEqualTo(snapshotId);
        assertThat(dto.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");
    }

    // WORKSPACE CASE — Efficient by construction: exactly one repository call for the invoice
    // list and one bulk call for history, never a per-invoice history lookup.
    @Test
    void getApprovalWorkspaceInvoices_multipleInvoices_fetchesHistoryInOneBulkCall() {
        Invoice first = persistedInvoice(
                "INV-1", "BS-1", UUID.randomUUID(), "Client One", "Project One");
        first.setInvoiceId(UUID.randomUUID());
        first.setStatus(InvoiceStatus.PENDING_APPROVAL);

        Invoice second = persistedInvoice(
                "INV-2", "BS-2", UUID.randomUUID(), "Client Two", "Project Two");
        second.setInvoiceId(UUID.randomUUID());
        second.setStatus(InvoiceStatus.APPROVED);

        when(invoiceRepository.findAllInApprovalWorkflowOrderByGeneratedAtDesc())
                .thenReturn(List.of(first, second));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdInOrderByActionAtAsc(any()))
                .thenReturn(List.of());

        invoiceService.getApprovalWorkspaceInvoices();

        verify(invoiceApprovalHistoryRepository, times(1))
                .findByInvoiceIdInOrderByActionAtAsc(any());
        verify(invoiceApprovalHistoryRepository, never())
                .findByInvoiceIdOrderByActionAtAsc(any());
        verify(invoiceApprovalHistoryRepository, never())
                .findTopByInvoiceIdAndActionOrderByActionAtDesc(any(), any());
    }

    // WORKSPACE CASE 9 — The existing pending-approval endpoint is unaffected: still only PENDING_APPROVAL.
    @Test
    void getPendingApprovalInvoices_stillReturnsOnlyPendingApprovalAfterWorkspaceAddition() {
        Invoice pending = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        pending.setInvoiceId(UUID.randomUUID());
        pending.setStatus(InvoiceStatus.PENDING_APPROVAL);

        when(invoiceRepository.findAllByStatusOrderByGeneratedAtDesc(InvoiceStatus.PENDING_APPROVAL))
                .thenReturn(List.of(pending));
        when(invoiceApprovalHistoryRepository.findTopByInvoiceIdAndActionOrderByActionAtDesc(any(), any()))
                .thenReturn(Optional.empty());

        List<InvoiceApprovalSummaryResponseDto> response = invoiceService.getPendingApprovalInvoices();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        // The workspace's EXISTS-based query is never touched by the pending-approval endpoint.
        verify(invoiceRepository, never()).findAllInApprovalWorkflowOrderByGeneratedAtDesc();
    }

    // WORKSPACE CASE 10 — The existing submit/approve flow is unaffected by the new endpoint's
    // presence: submitForApproval and approveInvoice behave exactly as before.
    @Test
    void submitAndApprove_stillWorkExactlyAsBeforeAfterWorkspaceAddition() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.GENERATED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceResponseDto submitted = invoiceService.submitForApproval(invoiceId);
        assertThat(submitted.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        InvoiceResponseDto approved = invoiceService.approveInvoice(invoiceId);
        assertThat(approved.getStatus()).isEqualTo(InvoiceStatus.APPROVED);

        verify(invoiceApprovalHistoryRepository, times(2)).save(any(InvoiceApprovalHistory.class));
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);
    }

    // =====================================================================
    // Invoice Correction — Phase 2B: refresh-after-correction
    // =====================================================================

    // REFRESH CASE 1/8/9/10/11/12/13/15 — A REJECTED invoice refreshes successfully:
    // status stays REJECTED, invoiceId/invoiceNumber preserved, financial fields, line
    // items, and tax components come from the authoritative BillingSnapshot/TaxCalculation,
    // and a CORRECTED history entry is recorded.
    @Test
    void refreshAfterCorrection_rejectedInvoice_refreshesFinancialSnapshotAndRecordsCorrectedHistory() {
        // refreshInvoiceFromAuthoritativeData copies the project dates from the billing configuration,
        // which BillingConfigurationService always supplies (it throws ResourceNotFound, never null).
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-STALE", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        // The invoice's frozen line items/tax components are stale (pre-correction) -
        // refresh must replace them entirely from the authoritative snapshot/tax calculation.
        invoice.getItems().add(InvoiceItem.builder()
                .invoiceItemId(UUID.randomUUID())
                .invoice(invoice)
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("Stale Line Item")
                .amount(new BigDecimal("999.00"))
                .build());
        invoice.getTaxComponents().add(InvoiceTaxComponent.builder()
                .invoiceTaxComponentId(UUID.randomUUID())
                .invoice(invoice)
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("STALE")
                .taxTypeName("Stale Tax")
                .appliedRate(new BigDecimal("1.0000"))
                .taxAmount(new BigDecimal("9.00"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .build());

        // Realistic pre-refresh state: the financial-correction flow has just
        // rebuilt the snapshot (READY_FOR_TAX) and recalculated tax
        // (TAX_COMPLETED) immediately before calling refreshAfterCorrection.
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceResponseDto response = invoiceService.refreshAfterCorrection(invoiceId);

        // The BillingSnapshot lifecycle is restored to INVOICED now that the
        // corrected invoice is refreshed and persisted - it must not be left
        // stranded at TAX_COMPLETED while an active invoice exists.
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        verify(billingSnapshotRepository, times(1)).save(snapshot);

        // Status stays REJECTED - correction alone never advances the workflow.
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        // Business identity preserved.
        assertThat(response.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(invoice.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-20260908170000");

        // Financial fields refreshed from the authoritative TaxCalculation.
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");
        assertThat(response.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");
        assertThat(response.getBillingPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(response.getBillingPeriodEnd()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(response.getDueDate()).isEqualTo(response.getInvoiceDate().plusDays(30));

        // Line items replaced entirely - the stale item is gone.
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getItemName()).isEqualTo("Backend Development");
        assertThat(response.getItems()).noneMatch(i -> "Stale Line Item".equals(i.getItemName()));

        // Tax components replaced entirely - the stale component is gone.
        assertThat(response.getTaxComponents()).hasSize(2);
        assertThat(response.getTaxComponents())
                .extracting(InvoiceTaxComponentResponseDto::getTaxTypeCode)
                .containsExactlyInAnyOrder("CGST", "SGST");

        // No tax recalculation - TaxCalculation is only ever read, never saved.
        verify(taxCalculationRepository, never()).save(any());

        // CORRECTED history entry recorded, status unchanged in the record itself.
        ArgumentCaptor<InvoiceApprovalHistory> historyCaptor = ArgumentCaptor.forClass(InvoiceApprovalHistory.class);
        verify(invoiceApprovalHistoryRepository, times(1)).save(historyCaptor.capture());
        InvoiceApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(history.getAction()).isEqualTo(InvoiceApprovalAction.CORRECTED);
        assertThat(history.getPreviousStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(history.getNewStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(history.getActionBy()).isEqualTo("SYSTEM");
        assertThat(history.getComment()).isEqualTo("Invoice refreshed from corrected billing/tax data.");
    }

    // REFRESH CASE 2/3/4 — Only REJECTED invoices can be refreshed.
    @Test
    void refreshAfterCorrection_generatedInvoice_throwsValidationException() {
        assertRefreshRejected(InvoiceStatus.GENERATED);
    }

    @Test
    void refreshAfterCorrection_pendingApprovalInvoice_throwsValidationException() {
        assertRefreshRejected(InvoiceStatus.PENDING_APPROVAL);
    }

    @Test
    void refreshAfterCorrection_approvedInvoice_throwsValidationException() {
        assertRefreshRejected(InvoiceStatus.APPROVED);
    }

    private void assertRefreshRejected(InvoiceStatus currentStatus) {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(currentStatus);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only rejected invoices can be refreshed after correction.");

        assertThat(invoice.getStatus()).isEqualTo(currentStatus);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);
    }

    // REFRESH CASE — Invoice not found.
    @Test
    void refreshAfterCorrection_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REFRESH CASE — BillingSnapshot has not completed tax calculation (e.g. still IN_TAX).
    @Test
    void refreshAfterCorrection_billingSnapshotNotTaxCompleted_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.IN_TAX);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice cannot be refreshed because this billing snapshot has not completed tax calculation.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
        verifyNoInteractions(taxCalculationRepository);
    }

    // REFRESH CASE — TaxCalculation missing for the snapshot.
    @Test
    void refreshAfterCorrection_taxCalculationMissing_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("No tax calculation has been completed for this billing snapshot. Invoice cannot be refreshed.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REFRESH CASE — TaxCalculation exists but has not completed successfully (FAILED).
    @Test
    void refreshAfterCorrection_taxCalculationFailed_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);
        TaxCalculation taxCalculation = completedTaxCalculation();
        taxCalculation.setStatus(TaxCalculationStatus.FAILED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice cannot be refreshed because tax calculation has not completed successfully.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REFRESH CASE — Inconsistent tax calculation totals are detected instead of silently accepted.
    @Test
    void refreshAfterCorrection_taxCalculationTotalsInconsistent_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);
        TaxCalculation taxCalculation = completedTaxCalculation();
        taxCalculation.setGrandTotal(new BigDecimal("9999.00")); // deliberately wrong

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Tax calculation totals are inconsistent for this billing snapshot. Invoice cannot be refreshed.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // REFRESH CASE 20 — Transaction integrity: if persisting the refreshed invoice fails,
    // no CORRECTED history entry is ever recorded (the audit trail cannot become
    // inconsistent with what was actually persisted).
    @Test
    void refreshAfterCorrection_persistenceFails_noHistoryRecorded() {
        // refreshInvoiceFromAuthoritativeData copies the project dates from the billing configuration,
        // which BillingConfigurationService always supplies (it throws ResourceNotFound, never null).
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class)))
                .thenThrow(new RuntimeException("unexpected database error"));

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(RuntimeException.class);

        // The @Transactional boundary rolls back the in-memory field/item/component
        // mutations at the database level; here we assert history is never even reached.
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // Root-cause regression: a refresh needs the invoice's billing configuration. When it cannot be
    // loaded the lookup throws ResourceNotFound, the refresh fails closed and nothing is persisted.
    @Test
    void refreshAfterCorrection_configurationMissing_failsClosedWithoutPersisting() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId))
                .thenReturn(Optional.of(completedTaxCalculation()));
        when(billingConfigurationService.getBillingConfiguration(any()))
                .thenThrow(new GlobalExceptionHandler.ResourceNotFoundException("Billing Configuration not found."));

        assertThatThrownBy(() -> invoiceService.refreshAfterCorrection(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class);

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // Root-cause regression: a refresh copies the project dates from the configuration onto the invoice.
    @Test
    void refreshAfterCorrection_copiesProjectDatesFromBillingConfiguration() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);
        invoice.setProjectStartDate(null);
        invoice.setProjectEndDate(null);
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId))
                .thenReturn(Optional.of(completedTaxCalculation()));
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        invoiceService.refreshAfterCorrection(invoiceId);

        assertThat(invoice.getProjectStartDate()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(invoice.getProjectEndDate()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    // RESUBMISSION SAFETY CASE 1 — A REJECTED invoice with no correction refresh cannot resubmit.
    @Test
    void submitForApproval_rejectedInvoiceWithoutCorrectionRefresh_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        LocalDateTime rejectedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, rejectedAt)
                ));

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.submitForApproval(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice must be refreshed after correction before resubmission.");

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(billingSnapshotRepository);
    }

    // RESUBMISSION SAFETY CASE 2 — A correction refresh recorded *before* the latest
    // rejection (i.e. belonging to an earlier rejection cycle) does not satisfy the
    // newer rejection - resubmission must still be blocked.
    @Test
    void submitForApproval_correctionFromEarlierRejectionCycle_doesNotSatisfyNewerRejection() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        LocalDateTime firstRejectedAt = LocalDateTime.of(2026, 9, 9, 9, 0);
        LocalDateTime firstCorrectedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        LocalDateTime secondRejectedAt = LocalDateTime.of(2026, 9, 9, 12, 0);

        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, firstRejectedAt),
                        historyEntry(invoiceId, InvoiceStatus.REJECTED, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.CORRECTED, firstCorrectedAt),
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, secondRejectedAt)
                ));

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.submitForApproval(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice must be refreshed after correction before resubmission.");

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        verify(invoiceRepository, never()).save(any());
    }

    // FULL CYCLE — GENERATED -> submit -> reject -> (blocked resubmit) -> refresh -> resubmit
    // -> reject again -> (blocked resubmit) -> refresh -> resubmit -> approve, across two full
    // rejection/correction cycles, driven entirely through the public service methods.
    @Test
    void fullCorrectionCycle_acrossTwoRejectionCycles_enforcesLatestCorrectionOnly() {
        // refreshInvoiceFromAuthoritativeData copies the project dates from the billing configuration,
        // which BillingConfigurationService always supplies (it throws ResourceNotFound, never null).
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.GENERATED);

        List<InvoiceApprovalHistory> savedHistory = new ArrayList<>();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceApprovalHistoryRepository.save(any(InvoiceApprovalHistory.class)))
                .thenAnswer(inv -> {
                    InvoiceApprovalHistory saved = inv.getArgument(0);
                    savedHistory.add(saved);
                    return saved;
                });
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenAnswer(inv -> new ArrayList<>(savedHistory));

        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setStatus(BillingSnapshotStatus.INVOICED);
        TaxCalculation taxCalculation = completedTaxCalculation();

        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));

        // Cycle 1.
        invoiceService.submitForApproval(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        invoiceService.rejectInvoice(invoiceId, InvoiceRejectionRequestDto.builder()
                .reason("First rejection reason.")
                .build());
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        assertThatThrownBy(() -> invoiceService.submitForApproval(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice must be refreshed after correction before resubmission.");

        InvoiceResponseDto refreshed = invoiceService.refreshAfterCorrection(invoiceId);
        assertThat(refreshed.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        invoiceService.submitForApproval(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        // Cycle 2 - a second, independent rejection/correction cycle.
        invoiceService.rejectInvoice(invoiceId, InvoiceRejectionRequestDto.builder()
                .reason("Second rejection reason.")
                .build());
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        assertThatThrownBy(() -> invoiceService.submitForApproval(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice must be refreshed after correction before resubmission.");

        invoiceService.refreshAfterCorrection(invoiceId);
        invoiceService.submitForApproval(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);

        invoiceService.approveInvoice(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.APPROVED);

        // Both rejection reasons preserved distinctly - the earlier REJECTED entry's
        // comment is never overwritten by the later cycle.
        List<InvoiceApprovalHistory> rejectedEntries = savedHistory.stream()
                .filter(h -> h.getAction() == InvoiceApprovalAction.REJECTED)
                .toList();
        assertThat(rejectedEntries).hasSize(2);
        assertThat(rejectedEntries.get(0).getComment()).isEqualTo("First rejection reason.");
        assertThat(rejectedEntries.get(1).getComment()).isEqualTo("Second rejection reason.");

        long correctedCount = savedHistory.stream()
                .filter(h -> h.getAction() == InvoiceApprovalAction.CORRECTED)
                .count();
        assertThat(correctedCount).isEqualTo(2);

        // Business identity preserved across every correction/resubmission cycle.
        assertThat(invoice.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-20260908170000");

        // The BillingSnapshot lifecycle must land back on INVOICED after each
        // correction cycle, never stranded at TAX_COMPLETED while the invoice
        // is active (approved, in this case).
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.INVOICED);
        verify(billingSnapshotRepository, times(2)).save(snapshot);
    }

    // WORKSPACE CASE — correctionRequired reflects history: true when REJECTED with no
    // CORRECTED entry since the latest REJECTED entry, false once corrected.
    @Test
    void getApprovalWorkspaceInvoices_rejectedInvoices_correctionRequiredReflectsHistory() {
        Invoice needsCorrection = persistedInvoice(
                "INV-NEEDS-CORRECTION", "BS-1", UUID.randomUUID(), "Client One", "Project One");
        needsCorrection.setInvoiceId(UUID.randomUUID());
        needsCorrection.setStatus(InvoiceStatus.REJECTED);

        Invoice corrected = persistedInvoice(
                "INV-CORRECTED", "BS-2", UUID.randomUUID(), "Client Two", "Project Two");
        corrected.setInvoiceId(UUID.randomUUID());
        corrected.setStatus(InvoiceStatus.REJECTED);

        LocalDateTime rejectedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        LocalDateTime correctedAt = LocalDateTime.of(2026, 9, 9, 11, 0);

        InvoiceApprovalHistory needsCorrectionRejected = historyEntry(
                needsCorrection.getInvoiceId(), InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                InvoiceApprovalAction.REJECTED, rejectedAt);

        InvoiceApprovalHistory correctedRejected = historyEntry(
                corrected.getInvoiceId(), InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                InvoiceApprovalAction.REJECTED, rejectedAt);
        InvoiceApprovalHistory correctedCorrection = historyEntry(
                corrected.getInvoiceId(), InvoiceStatus.REJECTED, InvoiceStatus.REJECTED,
                InvoiceApprovalAction.CORRECTED, correctedAt);

        when(invoiceRepository.findAllInApprovalWorkflowOrderByGeneratedAtDesc())
                .thenReturn(List.of(needsCorrection, corrected));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdInOrderByActionAtAsc(any()))
                .thenReturn(List.of(needsCorrectionRejected, correctedRejected, correctedCorrection));

        List<InvoiceApprovalWorkspaceResponseDto> response = invoiceService.getApprovalWorkspaceInvoices();

        assertThat(response).hasSize(2);

        InvoiceApprovalWorkspaceResponseDto needsCorrectionDto = response.stream()
                .filter(dto -> dto.getInvoiceId().equals(needsCorrection.getInvoiceId()))
                .findFirst().orElseThrow();
        assertThat(needsCorrectionDto.isCorrectionRequired()).isTrue();
        assertThat(needsCorrectionDto.getLastCorrectedAt()).isNull();

        InvoiceApprovalWorkspaceResponseDto correctedDto = response.stream()
                .filter(dto -> dto.getInvoiceId().equals(corrected.getInvoiceId()))
                .findFirst().orElseThrow();
        assertThat(correctedDto.isCorrectionRequired()).isFalse();
        assertThat(correctedDto.getLastCorrectedAt()).isEqualTo(correctedAt);

        // Still no per-invoice history query - bulk-fetched only.
        verify(invoiceApprovalHistoryRepository, times(1)).findByInvoiceIdInOrderByActionAtAsc(any());
        verify(invoiceApprovalHistoryRepository, never()).findByInvoiceIdOrderByActionAtAsc(any());
    }

    // WORKSPACE CASE — non-REJECTED statuses always report correctionRequired = false.
    @Test
    void getApprovalWorkspaceInvoices_approvedInvoice_correctionRequiredIsFalse() {
        Invoice approved = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        approved.setInvoiceId(UUID.randomUUID());
        approved.setStatus(InvoiceStatus.APPROVED);

        when(invoiceRepository.findAllInApprovalWorkflowOrderByGeneratedAtDesc())
                .thenReturn(List.of(approved));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdInOrderByActionAtAsc(any()))
                .thenReturn(List.of());

        List<InvoiceApprovalWorkspaceResponseDto> response = invoiceService.getApprovalWorkspaceInvoices();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).isCorrectionRequired()).isFalse();
        assertThat(response.get(0).getLastCorrectedAt()).isNull();
    }

    // =====================================================================
    // isCorrectionRequired — shared derivation reused by Phase 2B's
    // financial-correction/reacquisition orchestrator (and already by
    // submitForApproval/correctNonFinancialFields internally).
    // =====================================================================

    @Test
    void isCorrectionRequired_rejectedInvoiceNoCorrectionSinceRejection_returnsTrue() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, LocalDateTime.of(2026, 9, 9, 10, 0))
                ));

        assertThat(invoiceService.isCorrectionRequired(invoiceId)).isTrue();
    }

    @Test
    void isCorrectionRequired_rejectedInvoiceAlreadyCorrectedSinceRejection_returnsFalse() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, LocalDateTime.of(2026, 9, 9, 10, 0)),
                        historyEntry(invoiceId, InvoiceStatus.REJECTED, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.CORRECTED, LocalDateTime.of(2026, 9, 9, 11, 0))
                ));

        assertThat(invoiceService.isCorrectionRequired(invoiceId)).isFalse();
    }

    @Test
    void isCorrectionRequired_nonRejectedInvoice_returnsFalseWithoutQueryingHistory() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.GENERATED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThat(invoiceService.isCorrectionRequired(invoiceId)).isFalse();

        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    @Test
    void isCorrectionRequired_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.isCorrectionRequired(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");
    }

    // =====================================================================
    // Invoice Correction — Phase 2C: non-financial correction
    // (correctNonFinancialFields / PATCH /{invoiceId}/non-financial-correction)
    // =====================================================================

    // NON-FINANCIAL CORRECTION CASE 1/2/3/4/5/6/7/8/9/10 — A REJECTED invoice's clientName
    // and projectName are updated (trimmed), status stays REJECTED, invoiceId/invoiceNumber
    // are preserved, every financial value/relationship is untouched, the prior REJECTED
    // history entry is preserved, a CORRECTED history entry is recorded, and
    // correctionRequired becomes false once that CORRECTED entry exists.
    @Test
    void correctNonFinancialFields_rejectedInvoice_updatesClientAndProjectNameAndRecordsCorrectedHistory() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Stale Client Name", "Stale Project Name");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        LocalDateTime rejectedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        List<InvoiceApprovalHistory> savedHistory = new ArrayList<>(
                List.of(historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                        InvoiceApprovalAction.REJECTED, rejectedAt))
        );

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceApprovalHistoryRepository.save(any(InvoiceApprovalHistory.class)))
                .thenAnswer(inv -> {
                    InvoiceApprovalHistory saved = inv.getArgument(0);
                    savedHistory.add(saved);
                    return saved;
                });
        // Stateful, like a real repository: reflects the CORRECTED entry once it is saved,
        // so correctionRequired is computed from what was actually persisted, not a stale read.
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenAnswer(inv -> new ArrayList<>(savedHistory));

        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("  Corrected Client Name  ")
                .projectName("  Corrected Project Name  ")
                .build();

        InvoiceResponseDto response = invoiceService.correctNonFinancialFields(invoiceId, request);

        // clientName / projectName updated and trimmed - the only two fields changed.
        assertThat(response.getClientName()).isEqualTo("Corrected Client Name");
        assertThat(response.getProjectName()).isEqualTo("Corrected Project Name");
        assertThat(invoice.getClientName()).isEqualTo("Corrected Client Name");
        assertThat(invoice.getProjectName()).isEqualTo("Corrected Project Name");

        // Status stays REJECTED - correction alone never advances the workflow.
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        // Business identity preserved.
        assertThat(response.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(invoice.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-20260908170000");

        // Every financial value and every other field/reference is untouched.
        assertThat(response.getSubtotal()).isEqualByComparingTo("5500.00");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("6490.00");
        assertThat(response.getBillingSnapshotId()).isEqualTo(snapshotId);
        assertThat(response.getBillingSnapshotNumber()).isEqualTo("BS-20260908164549");
        assertThat(response.getTaxCalculationId()).isEqualTo(invoice.getTaxCalculationId());
        assertThat(response.getClientId()).isEqualTo(invoice.getClientId());
        assertThat(response.getProjectId()).isEqualTo(23L);
        assertThat(response.getBillingPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(response.getBillingPeriodEnd()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getPaymentTermCode()).isEqualTo("NET_30");
        assertThat(response.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(response.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTaxComponents()).isEmpty();

        // No financial correction path is invoked - not the BillingSnapshot/TaxCalculation flow.
        verifyNoInteractions(billingSnapshotRepository);
        verifyNoInteractions(taxCalculationRepository);
        verifyNoInteractions(paymentTermsMasterRepository);

        // correctionRequired becomes false once the CORRECTED entry exists - eligible for resubmission.
        assertThat(response.isCorrectionRequired()).isFalse();
        assertThat(response.getLastCorrectedAt()).isNotNull();

        // CORRECTED history entry recorded; the prior REJECTED entry is preserved, not overwritten.
        assertThat(savedHistory).hasSize(2);
        assertThat(savedHistory.get(0).getAction()).isEqualTo(InvoiceApprovalAction.REJECTED);
        InvoiceApprovalHistory history = savedHistory.get(1);
        assertThat(history.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(history.getAction()).isEqualTo(InvoiceApprovalAction.CORRECTED);
        assertThat(history.getPreviousStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(history.getNewStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(history.getActionBy()).isEqualTo("SYSTEM");
        assertThat(history.getComment()).isEqualTo("Non-financial invoice correction completed.");
    }

    // NON-FINANCIAL CORRECTION CASE — Invoice not found.
    @Test
    void correctNonFinancialFields_invoiceNotFound_throwsResourceNotFoundException() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("New Client")
                .projectName("New Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Invoice could not be found.");

        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 13/14/15 — Only REJECTED invoices can be corrected.
    @Test
    void correctNonFinancialFields_generatedInvoice_throwsValidationException() {
        assertNonFinancialCorrectionRejectedForStatus(InvoiceStatus.GENERATED);
    }

    @Test
    void correctNonFinancialFields_pendingApprovalInvoice_throwsValidationException() {
        assertNonFinancialCorrectionRejectedForStatus(InvoiceStatus.PENDING_APPROVAL);
    }

    @Test
    void correctNonFinancialFields_approvedInvoice_throwsValidationException() {
        assertNonFinancialCorrectionRejectedForStatus(InvoiceStatus.APPROVED);
    }

    private void assertNonFinancialCorrectionRejectedForStatus(InvoiceStatus currentStatus) {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(currentStatus);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("New Client")
                .projectName("New Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Only rejected invoices can be corrected.");

        assertThat(invoice.getStatus()).isEqualTo(currentStatus);
        assertThat(invoice.getClientName()).isEqualTo("Account Management");
        assertThat(invoice.getProjectName()).isEqualTo("Website Redesign");
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 19 — A REJECTED invoice already corrected since its
    // latest rejection (correctionRequired already false) cannot be corrected again until
    // a new rejection occurs. Mirrors the same cycle-aware guard used by submitForApproval.
    @Test
    void correctNonFinancialFields_alreadyCorrectedSinceLatestRejection_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        LocalDateTime rejectedAt = LocalDateTime.of(2026, 9, 9, 10, 0);
        LocalDateTime correctedAt = LocalDateTime.of(2026, 9, 9, 11, 0);
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenReturn(List.of(
                        historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.REJECTED, rejectedAt),
                        historyEntry(invoiceId, InvoiceStatus.REJECTED, InvoiceStatus.REJECTED,
                                InvoiceApprovalAction.CORRECTED, correctedAt)
                ));
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("New Client")
                .projectName("New Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice has already been corrected since its latest rejection. Wait for a new rejection before correcting again.");

        assertThat(invoice.getClientName()).isEqualTo("Account Management");
        assertThat(invoice.getProjectName()).isEqualTo("Website Redesign");
        verify(invoiceRepository, never()).save(any());
        verify(invoiceApprovalHistoryRepository, never()).save(any());
    }

    // NON-FINANCIAL CORRECTION CASE 16 — Null/blank clientName is rejected.
    @Test
    void correctNonFinancialFields_nullClientName_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName(null)
                .projectName("Valid Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Client name is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    @Test
    void correctNonFinancialFields_blankClientName_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("")
                .projectName("Valid Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Client name is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 18 — Whitespace-only clientName is rejected.
    @Test
    void correctNonFinancialFields_whitespaceOnlyClientName_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("   ")
                .projectName("Valid Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Client name is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 17 — Null/blank projectName is rejected.
    @Test
    void correctNonFinancialFields_nullProjectName_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("Valid Client")
                .projectName(null)
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Project name is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    @Test
    void correctNonFinancialFields_blankProjectName_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("Valid Client")
                .projectName("")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Project name is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 18 — Whitespace-only projectName is rejected.
    @Test
    void correctNonFinancialFields_whitespaceOnlyProjectName_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("Valid Client")
                .projectName("   ")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Project name is required.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 9 — clientName exceeding the 255-character column length
    // is rejected as a 400 business error, not a 500 persistence failure.
    @Test
    void correctNonFinancialFields_clientNameTooLong_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("A".repeat(256))
                .projectName("Valid Project")
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Client name must not exceed 255 characters.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    @Test
    void correctNonFinancialFields_projectNameTooLong_throwsValidationException() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("Valid Client")
                .projectName("A".repeat(256))
                .build();

        assertThatThrownBy(() -> invoiceService.correctNonFinancialFields(invoiceId, request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Project name must not exceed 255 characters.");

        verifyNoInteractions(invoiceRepository);
        verifyNoInteractions(invoiceApprovalHistoryRepository);
    }

    // NON-FINANCIAL CORRECTION CASE 11/12 — FULL CYCLE: a REJECTED invoice is corrected
    // (non-financial only) and then successfully resubmitted, recording the existing
    // SUBMITTED history entry - proving the two correction paths (Phase 2B financial refresh
    // and Phase 2C non-financial correction) both satisfy the same resubmission gate.
    @Test
    void correctNonFinancialFields_rejectedInvoice_canBeResubmittedAfterCorrection() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Stale Client Name", "Stale Project Name");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        List<InvoiceApprovalHistory> savedHistory = new ArrayList<>(
                List.of(historyEntry(invoiceId, InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.REJECTED,
                        InvoiceApprovalAction.REJECTED, LocalDateTime.of(2026, 9, 9, 10, 0)))
        );

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceApprovalHistoryRepository.save(any(InvoiceApprovalHistory.class)))
                .thenAnswer(inv -> {
                    InvoiceApprovalHistory saved = inv.getArgument(0);
                    savedHistory.add(saved);
                    return saved;
                });
        when(invoiceApprovalHistoryRepository.findByInvoiceIdOrderByActionAtAsc(invoiceId))
                .thenAnswer(inv -> new ArrayList<>(savedHistory));

        InvoiceNonFinancialCorrectionRequestDto request = InvoiceNonFinancialCorrectionRequestDto.builder()
                .clientName("Corrected Client Name")
                .projectName("Corrected Project Name")
                .build();

        InvoiceResponseDto corrected = invoiceService.correctNonFinancialFields(invoiceId, request);
        assertThat(corrected.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(corrected.isCorrectionRequired()).isFalse();

        // Not auto-resubmitted - still REJECTED until submitForApproval is explicitly called.
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);

        InvoiceResponseDto resubmitted = invoiceService.submitForApproval(invoiceId);

        assertThat(resubmitted.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        // Business identity and the corrected names survive resubmission.
        assertThat(resubmitted.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(resubmitted.getInvoiceNumber()).isEqualTo("INV-20260908170000");
        assertThat(resubmitted.getClientName()).isEqualTo("Corrected Client Name");
        assertThat(resubmitted.getProjectName()).isEqualTo("Corrected Project Name");

        // SUBMITTED history entry recorded for the resubmission, with the correction-resubmission comment.
        InvoiceApprovalHistory submittedEntry = savedHistory.get(savedHistory.size() - 1);
        assertThat(submittedEntry.getAction()).isEqualTo(InvoiceApprovalAction.SUBMITTED);
        assertThat(submittedEntry.getPreviousStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(submittedEntry.getNewStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
        assertThat(submittedEntry.getComment()).isEqualTo("Invoice resubmitted for approval after correction.");

        // Full history preserved: REJECTED, CORRECTED, SUBMITTED - nothing lost or overwritten.
        assertThat(savedHistory).extracting(InvoiceApprovalHistory::getAction)
                .containsExactly(
                        InvoiceApprovalAction.REJECTED,
                        InvoiceApprovalAction.CORRECTED,
                        InvoiceApprovalAction.SUBMITTED
                );
    }

    // =========================================================
    // Tax breakdown and tax context on the persisted invoice
    // =========================================================

    private BillingSnapshot taxCompletedSnapshotWithTaxContext() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        snapshot.setTaxRegionCode("IN");
        snapshot.setSourceTaxJurisdictionCode("IN");
        snapshot.setDestinationTaxJurisdictionCode("IN");
        return snapshot;
    }

    private void stubSnapshotInvoiceGeneration(BillingSnapshot snapshot, TaxCalculation taxCalculation) {
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingSnapshotId(snapshotId)).thenReturn(false);
    }

    // Tests 1-3: components persisted, associated with the invoice, and summing to its tax total.
    @Test
    void generateInvoice_persistsTaxComponentsOnInvoiceWithTotalEqualToTheirSum() {
        BillingSnapshot snapshot = taxCompletedSnapshotWithTaxContext();
        TaxCalculation taxCalculation = completedTaxCalculation();
        stubSnapshotInvoiceGeneration(snapshot, taxCalculation);
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        ArgumentCaptor<Invoice> savedCaptor = ArgumentCaptor.forClass(Invoice.class);
        when(invoiceRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        Invoice persisted = savedCaptor.getValue();
        assertThat(persisted.getTaxComponents()).hasSize(2);
        assertThat(persisted.getTaxComponents()).allSatisfy(component ->
                assertThat(component.getInvoice()).isSameAs(persisted));
        assertThat(persisted.getTaxComponents())
                .extracting(InvoiceTaxComponent::getTaxTypeCode, InvoiceTaxComponent::getAppliedRate,
                        InvoiceTaxComponent::getApplicabilityType)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("CGST", new BigDecimal("9.0000"), TaxApplicabilityType.ALL),
                        org.assertj.core.groups.Tuple.tuple("SGST", new BigDecimal("9.0000"), TaxApplicabilityType.ALL));

        BigDecimal componentTotal = persisted.getTaxComponents().stream()
                .map(InvoiceTaxComponent::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(persisted.getTotalTaxAmount()).isEqualByComparingTo(componentTotal);
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");

        // Tax context frozen from the snapshot the tax was calculated for.
        assertThat(persisted.getTaxRegionCode()).isEqualTo("IN");
        assertThat(response.getTaxRegionCode()).isEqualTo("IN");
        assertThat(response.getSourceTaxJurisdictionCode()).isEqualTo("IN");
        assertThat(response.getDestinationTaxJurisdictionCode()).isEqualTo("IN");
    }

    // Test 6: jurisdictions the snapshot never recorded stay null - never derived.
    @Test
    void generateInvoice_snapshotWithoutJurisdictions_leavesTaxContextNull() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        stubSnapshotInvoiceGeneration(snapshot, completedTaxCalculation());
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingSnapshotRepository.save(any(BillingSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoice(snapshotId);

        assertThat(response.getTaxRegionCode()).isNull();
        assertThat(response.getSourceTaxJurisdictionCode()).isNull();
        assertThat(response.getDestinationTaxJurisdictionCode()).isNull();
    }

    // Test 5: a tax calculation that did not complete successfully is rejected explicitly.
    @Test
    void generateInvoice_taxCalculationNotCalculated_throwsValidationExceptionAndPersistsNothing() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();
        taxCalculation.setStatus(TaxCalculationStatus.FAILED);
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice cannot be generated because the tax calculation has not completed successfully.");

        verify(invoiceRepository, never()).save(any());
        assertThat(snapshot.getStatus()).isEqualTo(BillingSnapshotStatus.TAX_COMPLETED);
    }

    // Test 3: the breakdown must explain the tax charged.
    @Test
    void generateInvoice_componentsDoNotSumToTotalTax_throwsValidationExceptionAndPersistsNothing() {
        BillingSnapshot snapshot = taxCompletedSnapshot();
        TaxCalculation taxCalculation = completedTaxCalculation();
        // Totals still self-consistent (5500 + 990 = 6490), but components sum to 895.00.
        taxCalculation.getComponents().get(1).setTaxAmount(new BigDecimal("400.00"));
        stubSnapshotInvoiceGeneration(snapshot, taxCalculation);

        assertThatThrownBy(() -> invoiceService.generateInvoice(snapshotId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Tax calculation totals are inconsistent for this billing snapshot. Invoice cannot be generated.");

        verify(invoiceRepository, never()).save(any());
    }

    // Test 4: the detail API returns the persisted breakdown and tax context -
    // no dependency on the (possibly since-changed) tax calculation.
    @Test
    void getInvoiceByBillingSnapshotId_returnsPersistedTaxBreakdownAndContext() {
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(UUID.randomUUID());
        invoice.setTaxRegionCode("IN");
        invoice.setSourceTaxJurisdictionCode("IN");
        invoice.setDestinationTaxJurisdictionCode("US");
        UUID componentId = UUID.randomUUID();
        UUID taxTypeId = UUID.randomUUID();
        invoice.getTaxComponents().add(InvoiceTaxComponent.builder()
                .invoiceTaxComponentId(componentId)
                .invoice(invoice)
                .taxTypeId(taxTypeId)
                .taxTypeCode("IGST")
                .taxTypeName("Integrated GST")
                .appliedRate(new BigDecimal("18.0000"))
                .taxAmount(new BigDecimal("990.00"))
                .applicabilityType(TaxApplicabilityType.DIFFERENT_JURISDICTION)
                .build());

        when(billingSnapshotRepository.existsById(snapshotId)).thenReturn(true);
        when(invoiceRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(invoice));

        InvoiceResponseDto response = invoiceService.getInvoiceByBillingSnapshotId(snapshotId);

        assertThat(response.getTaxComponents()).singleElement().satisfies(component -> {
            assertThat(component.getInvoiceTaxComponentId()).isEqualTo(componentId);
            assertThat(component.getTaxTypeId()).isEqualTo(taxTypeId);
            assertThat(component.getTaxTypeCode()).isEqualTo("IGST");
            assertThat(component.getTaxTypeName()).isEqualTo("Integrated GST");
            assertThat(component.getAppliedRate()).isEqualByComparingTo("18.0000");
            assertThat(component.getTaxAmount()).isEqualByComparingTo("990.00");
            assertThat(component.getApplicabilityType()).isEqualTo(TaxApplicabilityType.DIFFERENT_JURISDICTION);
        });
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getTaxRegionCode()).isEqualTo("IN");
        assertThat(response.getSourceTaxJurisdictionCode()).isEqualTo("IN");
        assertThat(response.getDestinationTaxJurisdictionCode()).isEqualTo("US");
        verify(taxCalculationRepository, never()).findByBillingSnapshotId(any());
    }

    // Test 7: repeated refresh replaces tax components; it never accumulates duplicates.
    @Test
    void refreshAfterCorrection_repeated_keepsExactlyTheCalculationsComponents() {
        // refreshInvoiceFromAuthoritativeData copies the project dates from the billing configuration,
        // which BillingConfigurationService always supplies (it throws ResourceNotFound, never null).
        when(billingConfigurationService.getBillingConfiguration(any())).thenReturn(configuration());
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = persistedInvoice(
                "INV-20260908170000", "BS-20260908164549", snapshotId,
                "Account Management", "Website Redesign");
        invoice.setInvoiceId(invoiceId);
        invoice.setStatus(InvoiceStatus.REJECTED);

        BillingSnapshot snapshot = taxCompletedSnapshotWithTaxContext();
        TaxCalculation taxCalculation = completedTaxCalculation();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(billingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(taxCalculationRepository.findByBillingSnapshotId(snapshotId)).thenReturn(Optional.of(taxCalculation));
        when(paymentTermsMasterRepository.findById(paymentTermId)).thenReturn(Optional.of(paymentTerms()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        invoiceService.refreshAfterCorrection(invoiceId);
        InvoiceResponseDto response = invoiceService.refreshAfterCorrection(invoiceId);

        assertThat(invoice.getTaxComponents()).hasSize(2);
        assertThat(response.getTaxComponents())
                .extracting(InvoiceTaxComponentResponseDto::getTaxTypeCode)
                .containsExactlyInAnyOrder("CGST", "SGST");
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("990.00");
        assertThat(response.getTaxRegionCode()).isEqualTo("IN");
    }

    // Schedule path: only the Tax Region is recorded by its tax calculation.
    @Test
    void generateInvoiceForSchedule_freezesTaxRegionAndLeavesUnrecordedJurisdictionsNull() {
        BillingSchedule schedule = taxCalculatedSchedule();
        TaxCalculation taxCalculation = completedScheduleTaxCalculation(schedule.getBillingScheduleId());

        when(billingScheduleRepository.findByIdForUpdate(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(schedule));
        when(taxCalculationRepository.findByBillingScheduleId(schedule.getBillingScheduleId()))
                .thenReturn(Optional.of(taxCalculation));
        when(invoiceRepository.existsByBillingScheduleId(schedule.getBillingScheduleId())).thenReturn(false);
        when(billingConfigurationService.getBillingConfiguration(any()))
                .thenReturn(BillingConfigurationResponseDto.builder()
                        .clientId(clientId)
                        .projectId(23L)
                        .currencyCode("USD")
                        .taxRegionCode("IN")
                        .build());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingScheduleRepository.save(any(BillingSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceResponseDto response = invoiceService.generateInvoiceForSchedule(schedule.getBillingScheduleId());

        assertThat(response.getTaxComponents()).hasSize(2);
        assertThat(response.getTotalTaxAmount()).isEqualByComparingTo("450.00");
        assertThat(response.getTaxRegionCode()).isEqualTo("IN");
        assertThat(response.getSourceTaxJurisdictionCode()).isNull();
        assertThat(response.getDestinationTaxJurisdictionCode()).isNull();
    }
}
