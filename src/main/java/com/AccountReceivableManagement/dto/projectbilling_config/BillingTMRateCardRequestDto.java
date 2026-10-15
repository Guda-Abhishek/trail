package com.AccountReceivableManagement.dto.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BillingTMRateCardRequestDto {
    private String roleName;

    @NotNull(message = "Rate is required.")
    @DecimalMin(value = "0.01", message = "Rate must be greater than zero.")
    private BigDecimal rate;

    @NotNull(message = "Rate period is required.")
    private BillingRatePeriod ratePeriod;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private String remarks;

    /** Bills work at the base rate when no discount period applies. Defaults to true on create; omitted on update keeps the current value. */
    private Boolean includeBaseRateInInvoice;
}
