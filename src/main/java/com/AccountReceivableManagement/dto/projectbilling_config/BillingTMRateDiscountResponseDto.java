package com.AccountReceivableManagement.dto.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.DiscountType;
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
public class BillingTMRateDiscountResponseDto {

    private UUID discountId;
    private UUID rateCardId;
    private String periodName;
    private DiscountType discountType;
    private BigDecimal discountValue;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String notes;
    private Boolean includeInInvoice;

    /** Billing configuration currency (authoritative for base rate, discount and net rate). */
    private String currencyCode;
    private BillingRatePeriod ratePeriod;

    // Derived from the rate card's current base rate, for display only (snapshots keep their own copy).
    private BigDecimal baseRate;
    private BigDecimal discountAmount;
    private BigDecimal netRate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
