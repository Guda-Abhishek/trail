package com.AccountReceivableManagement.dto.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
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
public class BillingTMRateCardResponseDto {

    private UUID rateCardId;

    private String roleName;

    private BigDecimal rate;

    private BillingRatePeriod ratePeriod;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private String remarks;

    private Boolean includeBaseRateInInvoice;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
