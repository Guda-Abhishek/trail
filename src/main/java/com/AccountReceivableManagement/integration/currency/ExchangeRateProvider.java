package com.AccountReceivableManagement.integration.currency;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Approved source of exchange rates for expense conversion.
 *
 * <p>There is deliberately NO default implementation: until Finance approves a
 * rate source and its policy, no bean of this type exists and every
 * cross-currency expense fails the acquisition. Implementations must return
 * only approved rates and must never estimate, interpolate or fall back to an
 * unapproved value - an absent or unsupported pair is {@link Optional#empty()}.
 */
public interface ExchangeRateProvider {

    /**
     * The approved rate converting {@code sourceCurrency} to {@code targetCurrency}
     * that applies on {@code rateDate}, or empty when none is available.
     */
    Optional<ExchangeRate> findRate(String sourceCurrency, String targetCurrency, LocalDate rateDate);
}
