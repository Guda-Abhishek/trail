package com.AccountReceivableManagement.dto.billing_data_acquisition;

import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Epic 2's own consumer-side view of an approved Billing Configuration,
 * as returned by {@code BillingConfigurationIntegration}. Mirrors Epic 1's
 * {@code GET /billing-configurations/project/{projectId}} response, but is
 * defined locally so Epic 2 never depends on Epic 1's internal DTOs.
 * Scoped to Story 2.1 (Time &amp; Material) only.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingConfigurationResponseDto {

    private UUID billingConfigurationId;

    private Long projectId;

    /**
     * Internal dispatch key only, derived from Epic 1's Billing Type master
     * name at acquisition time so the service layer can resolve a
     * {@code BillingAcquisitionStrategy}. Never persisted;
     * {@link #billingTypeId} is the master reference of record.
     */
    private BillingType billingType;

    private UUID billingTypeId;

    private String billingTypeName;

    private UUID currencyId;

    private String currencyCode;

    private UUID paymentTermId;

    private String paymentTermCode;

    private String paymentTermName;

    private UUID billingFrequencyId;

    private String billingFrequencyName;

    private UUID taxRegionId;

    private String taxRegionCode;

    private BigDecimal hourlyRate;

    /** Null is treated as STANDARD. */
    private com.AccountReceivableManagement.entity_enums.projectbilling_config.PricingModel pricingModel;

    private boolean expenseEligible;

    private boolean approved;
}
