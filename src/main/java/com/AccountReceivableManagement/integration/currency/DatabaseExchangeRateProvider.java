package com.AccountReceivableManagement.integration.currency;

import com.AccountReceivableManagement.entity.currency.ExchangeRateRecord;
import com.AccountReceivableManagement.repo.currency.ExchangeRateRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * {@link ExchangeRateProvider} backed by the Finance-maintained {@code currency_exchange_rate} table.
 *
 * <p>Opt-in only: the bean exists only when {@code ar.expense-fx.provider=database}. It looks up the
 * single rate for the exact pair and date - there is deliberately no fallback to an earlier date, no
 * interpolation and no external source; a missing rate is {@link Optional#empty()} and the conversion
 * fails closed. The table holds only what Finance loaded: nothing is seeded or defaulted here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ar.expense-fx", name = "provider", havingValue = "database")
public class DatabaseExchangeRateProvider implements ExchangeRateProvider {

    /** billing_snapshot_item.exchange_rate_source is VARCHAR(255). */
    static final int MAX_REFERENCE_LENGTH = 255;

    private final ExchangeRateRecordRepository repository;

    @Override
    public Optional<ExchangeRate> findRate(String sourceCurrency, String targetCurrency, LocalDate rateDate) {
        String from = normalise(sourceCurrency);
        String to = normalise(targetCurrency);
        if (from == null || to == null || rateDate == null) {
            return Optional.empty();
        }
        try {
            return repository.findBySourceCurrencyAndTargetCurrencyAndEffectiveDate(from, to, rateDate)
                    .map(DatabaseExchangeRateProvider::toExchangeRate);
        } catch (DataAccessException ex) {
            // Full detail stays in the server log; the caller only ever sees the generic public message.
            log.error("[ExchangeRateLookupFailed] pair={}->{} date={}", from, to, rateDate, ex);
            throw new ExchangeRateSourceUnavailableException(ex);
        }
    }

    private static ExchangeRate toExchangeRate(ExchangeRateRecord record) {
        // The reference ties the persisted snapshot line back to the approved row (id, source).
        String reference = record.getId() + "; " + record.getRateSource();
        if (reference.length() > MAX_REFERENCE_LENGTH) {
            reference = reference.substring(0, MAX_REFERENCE_LENGTH);
        }
        return new ExchangeRate(record.getSourceCurrency(), record.getTargetCurrency(), record.getRate(),
                record.getEffectiveDate(), reference);
    }

    private static String normalise(String currency) {
        return currency == null || currency.isBlank() ? null : currency.trim().toUpperCase();
    }
}
