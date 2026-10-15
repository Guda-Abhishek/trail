package com.AccountReceivableManagement.integration.currency;

import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Converts an XMS expense amount into the billing currency - only when the
 * currencies differ. Same-currency amounts are returned untouched and the rate
 * provider is never consulted. Cross-currency conversion fails closed (a
 * {@link GlobalExceptionHandler.ValidationException} with an actionable
 * message) unless the policy ({@link ExpenseFxProperties}) is fully configured
 * AND an {@link ExchangeRateProvider} bean exists AND it returns an approved
 * rate. Arithmetic is {@link BigDecimal} only.
 */
@Component
@RequiredArgsConstructor
public class ExpenseCurrencyConverter {

    /** The billed amount column is DECIMAL(19,2). */
    static final int MAX_BILLED_SCALE = 2;

    private final ExpenseFxProperties properties;
    private final ObjectProvider<ExchangeRateProvider> rateProvider;

    public ConvertedAmount convert(BigDecimal amount, String sourceCurrency, String targetCurrency,
                                   LocalDate expenseDate, LocalDate billingPeriodEnd) {
        String source = normalise(sourceCurrency);
        String target = normalise(targetCurrency);
        if (amount == null || source == null || target == null) {
            throw failure("amount, source currency and billing currency are all required for currency handling.");
        }
        if (source.equals(target)) {
            return ConvertedAmount.unchanged(amount, source);
        }

        String pair = source + " to " + target;
        requirePolicy(pair);
        ExchangeRateProvider provider = rateProvider.getIfAvailable();
        if (provider == null) {
            throw failure("no approved exchange-rate source is configured, so " + pair
                    + " cannot be converted. Cross-currency expenses are blocked until Finance approves a rate source.");
        }

        LocalDate rateDate = properties.getRateDateBasis() == ExpenseFxProperties.RateDateBasis.EXPENSE_DATE
                ? expenseDate : billingPeriodEnd;
        if (rateDate == null) {
            throw failure("the rate date (" + properties.getRateDateBasis() + ") is unknown for this expense.");
        }

        Optional<ExchangeRate> found = provider.findRate(source, target, rateDate);
        if (found == null || found.isEmpty()) {
            throw failure("no approved exchange rate is available for " + pair + " on " + rateDate
                    + ", so the expense cannot be converted. Ask Finance to add an approved rate.");
        }
        ExchangeRate rate = found.get();
        if (!source.equals(normalise(rate.sourceCurrency())) || !target.equals(normalise(rate.targetCurrency()))) {
            throw failure("the rate provider returned a " + rate.sourceCurrency() + " to " + rate.targetCurrency()
                    + " rate for a " + pair + " request.");
        }
        if (rate.rate() == null || rate.rate().signum() <= 0) {
            throw failure("the approved " + pair + " rate is missing or not positive.");
        }
        if (rate.effectiveDate() == null || rate.effectiveDate().isAfter(rateDate)) {
            throw failure("the " + pair + " rate is not effective on " + rateDate + ".");
        }

        BigDecimal converted = amount.multiply(rate.rate())
                .setScale(properties.getScale(), properties.getRoundingMode());
        return new ConvertedAmount(amount, source, converted, target, rate.rate(),
                rate.effectiveDate(), rate.sourceReference(), true);
    }

    private void requirePolicy(String pair) {
        if (properties.getScale() == null || properties.getRoundingMode() == null
                || properties.getRateDateBasis() == null) {
            throw failure("the currency-conversion policy has not been approved/configured, so " + pair
                    + " cannot be converted (ar.expense-fx.scale, ar.expense-fx.rounding-mode and "
                    + "ar.expense-fx.rate-date-basis are all required).");
        }
        if (properties.getScale() < 0 || properties.getScale() > MAX_BILLED_SCALE) {
            throw failure("ar.expense-fx.scale must be between 0 and " + MAX_BILLED_SCALE
                    + " (the billed amount is stored with 2 decimals).");
        }
    }

    private static String normalise(String currency) {
        return currency == null || currency.isBlank() ? null : currency.trim().toUpperCase();
    }

    private static GlobalExceptionHandler.ValidationException failure(String reason) {
        return new GlobalExceptionHandler.ValidationException("Expense currency conversion failed: " + reason);
    }
}
