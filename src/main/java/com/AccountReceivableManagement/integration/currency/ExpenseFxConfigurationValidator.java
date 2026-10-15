package com.AccountReceivableManagement.integration.currency;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup check, active only when the database rate provider is explicitly enabled
 * ({@code ar.expense-fx.provider=database}). Enabling the provider without a complete, in-range policy
 * would only produce a failure on the first cross-currency expense, so the application refuses to start
 * and names every problem instead. It checks completeness and range only - it never supplies a value.
 * When the provider is not enabled this bean does not exist and conversion stays fail-closed at use time.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ar.expense-fx", name = "provider", havingValue = "database")
public class ExpenseFxConfigurationValidator implements InitializingBean {

    private final ExpenseFxProperties properties;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(properties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("ar.expense-fx.provider=database is enabled but the currency-conversion "
                    + "policy is not valid: " + String.join("; ", problems) + ".");
        }
    }

    static List<String> problems(ExpenseFxProperties properties) {
        List<String> problems = new ArrayList<>();
        if (properties.getScale() == null) {
            problems.add("ar.expense-fx.scale is required");
        } else if (properties.getScale() < 0 || properties.getScale() > ExpenseCurrencyConverter.MAX_BILLED_SCALE) {
            problems.add("ar.expense-fx.scale must be between 0 and " + ExpenseCurrencyConverter.MAX_BILLED_SCALE);
        }
        if (properties.getRoundingMode() == null) {
            problems.add("ar.expense-fx.rounding-mode is required");
        }
        if (properties.getRateDateBasis() == null) {
            problems.add("ar.expense-fx.rate-date-basis is required");
        }
        return problems;
    }
}
