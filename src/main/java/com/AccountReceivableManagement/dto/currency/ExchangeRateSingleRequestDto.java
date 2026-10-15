package com.AccountReceivableManagement.dto.currency;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateSingleRequestDto {

    @NotBlank(message = "Source currency is required.")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Source currency must be a 3-letter currency code.")
    private String sourceCurrency;

    @NotBlank(message = "Target currency is required.")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Target currency must be a 3-letter currency code.")
    private String targetCurrency;

    private BigDecimal exchangeRate;
    private BigDecimal rate;

    @NotNull(message = "Effective date is required.")
    private LocalDate effectiveDate;

    @NotBlank(message = "Rate source is required.")
    private String rateSource;

    private String status;
    private String notes;

    public BigDecimal getEffectiveRate() {
        return exchangeRate != null ? exchangeRate : rate;
    }
}
