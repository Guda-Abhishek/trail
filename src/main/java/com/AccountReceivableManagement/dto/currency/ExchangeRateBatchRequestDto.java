package com.AccountReceivableManagement.dto.currency;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateBatchRequestDto {

    @NotBlank(message = "Source currency is required.")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Source currency must be a 3-letter currency code.")
    private String sourceCurrency;

    @NotNull(message = "Effective date is required.")
    private LocalDate effectiveDate;

    @NotBlank(message = "Rate source is required.")
    private String rateSource;

    @NotEmpty(message = "At least one exchange rate must be provided.")
    @Valid
    private List<ExchangeRateItemDto> rates;
}
