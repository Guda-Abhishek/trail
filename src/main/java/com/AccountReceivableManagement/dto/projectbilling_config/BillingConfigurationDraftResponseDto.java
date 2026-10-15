package com.AccountReceivableManagement.dto.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingConfigurationStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.InvoiceGenerationType;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.PricingModel;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingConfigurationDraftResponseDto {

    private UUID billingConfigurationId;

    private UUID clientId;

    private Long projectId;

    /** Numeric PMS project id of the configured project; null when the draft has no project. */
    private Long pmsProjectId;

    private UUID billingTypeId;

    private UUID billingFrequencyId;

    private UUID currencyId;

    private String currency;

    private UUID paymentTermId;

    private UUID taxRegionId;

    private PricingModel pricingModel;

    private InvoiceGenerationType invoiceGenerationType;

    private Boolean expenseBillingEligible;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private BigDecimal hourlyRate;


    private BillingConfigurationStatus status;

    private Boolean isActive;
}
