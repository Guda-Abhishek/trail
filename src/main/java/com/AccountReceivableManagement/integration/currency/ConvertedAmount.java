package com.AccountReceivableManagement.integration.currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of {@link ExpenseCurrencyConverter#convert}. When {@code converted}
 * is false the amount is exactly the input and the rate fields are null.
 */
public record ConvertedAmount(BigDecimal sourceAmount,
                              String sourceCurrency,
                              BigDecimal amount,
                              String currency,
                              BigDecimal rate,
                              LocalDate rateEffectiveDate,
                              String rateSourceReference,
                              boolean converted) {

    static ConvertedAmount unchanged(BigDecimal amount, String currency) {
        return new ConvertedAmount(amount, currency, amount, currency, null, null, null, false);
    }
}
