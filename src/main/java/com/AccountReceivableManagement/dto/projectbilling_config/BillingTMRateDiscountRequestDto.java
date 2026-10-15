package com.AccountReceivableManagement.dto.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BillingTMRateDiscountRequestDto {

    @NotBlank(message = "Period name is required.")
    @Size(max = 150, message = "Period name must be at most 150 characters.")
    private String periodName;

    @NotNull(message = "Discount type is required.")
    private DiscountType discountType;

    @NotNull(message = "Discount value is required.")
    @DecimalMin(value = "0.01", message = "Discount value must be greater than zero.")
    private BigDecimal discountValue;

    @NotNull(message = "Effective From is required.")
    private LocalDate effectiveFrom;

    @NotNull(message = "Effective To is required.")
    private LocalDate effectiveTo;

    @Size(max = 500, message = "Notes must be at most 500 characters.")
    private String notes;

    /** Defaults to true when omitted. */
    private Boolean includeInInvoice;

    /**
     * Optional. The currency is always the billing configuration's; when supplied it must match
     * (case-insensitive), otherwise the request is rejected.
     */
    private String currencyCode;
}
