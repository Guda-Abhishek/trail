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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateItemDto {

    @NotBlank(message = "Target currency is required.")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Target currency must be a 3-letter currency code.")
    private String targetCurrency;

    private BigDecimal exchangeRate;
    private BigDecimal rate;

    public BigDecimal getEffectiveRate() {
        return exchangeRate != null ? exchangeRate : rate;
    }
}
