package com.AccountReceivableManagement.integration.currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One approved exchange rate.
 *
 * @param sourceCurrency  ISO code the rate converts from
 * @param targetCurrency  ISO code the rate converts to
 * @param rate            units of {@code targetCurrency} per 1 unit of {@code sourceCurrency}
 *                        (so {@code targetAmount = sourceAmount x rate})
 * @param effectiveDate   the date the rate is effective for
 * @param sourceReference where the rate comes from (table row id, provider reference) - audit trail
 */
public record ExchangeRate(String sourceCurrency,
                           String targetCurrency,
                           BigDecimal rate,
                           LocalDate effectiveDate,
                           String sourceReference) {
}
