    package com.AccountReceivableManagement.dto.projectbilling_config;

    import com.AccountReceivableManagement.entity_enums.projectbilling_config.*;
    import lombok.*;

    import java.math.BigDecimal;
    import java.time.LocalDate;
    import java.time.LocalDateTime;
    import java.util.UUID;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public class BillingConfigurationResponseDto {

        private UUID billingConfigurationId;

        private UUID clientId;

        private String clientName;

        private String countryCode;

        private String countryName;

        private String email;

        private String phone;

        private Long projectId;

        private String projectName;

        private String primaryLocation;

        private LocalDate projectStartDate;

        private LocalDate projectEndDate;

        private UUID billingTypeId;

        private String billingTypeName;

        private String projectCode;

        private UUID currencyId;

        private String currencyCode;

        private String currency;

        private BigDecimal projectBudget;

        private BigDecimal contractValue;

        private String projectBudgetCurrency;

        private UUID paymentTermId;

        private String paymentTermCode;

        private String paymentTermName;

        private UUID billingFrequencyId;

        private String billingFrequencyName;

        private UUID taxRegionId;

        private PricingModel pricingModel;

        private ApprovalStatus approvalStatus;

        private BillingConfigurationStatus billingStatus;

        private InvoiceGenerationType invoiceGenerationType;

//        private GenerationMode generationMode;

        private String taxRegionName;

        private String taxRegionCode;

        private Boolean expenseBillingEligible;

        private String rejectionReason;

        private LocalDate effectiveFrom;

        private LocalDate effectiveTo;

        private LocalDateTime createdAt;

        private LocalDateTime updatedAt;

        private Integer versionNo;

        private String createdBy;

        private LocalDateTime createdDate;

        private BigDecimal hourlyRate;

        private BillingContext billingContext;

        private String productName;

        private String productDescription;

    // Billing-specific details
    private BillingFixedPriceResponseDto fixedPriceDetails;
    private RecurringBillingResponseDto recurringDetails;
    private java.util.List<BillingTMRateCardResponseDto> tmRateCards;
    private java.util.List<BillingScheduleResponseDto> milestoneSchedules;

    // Milestone Plan: the configured plan (payment structure + payment entries).
    // milestoneSchedules above are the generated occurrences, which only exist after approval.
    private PaymentStructure paymentStructure;
    private BillingMilestonePlanResponseDto milestonePlanDetails;

    // Change tracking for pending approval
    private java.util.List<BillingConfigurationChangeDto> changes;
    private String previousApprovalStatus;
    private String previousBillingStatus;

    }
