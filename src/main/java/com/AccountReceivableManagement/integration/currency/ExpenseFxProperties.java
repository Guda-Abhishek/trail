package com.AccountReceivableManagement.integration.currency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

/**
 * Expense currency-conversion policy ({@code ar.expense-fx.*}). Every value is
 * intentionally unset by default: Finance must approve them, and while any is
 * missing cross-currency expenses fail closed.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ar.expense-fx")
public class ExpenseFxProperties {

    /** Which date selects the rate. */
    public enum RateDateBasis {
        /** The expense's own date. */
        EXPENSE_DATE,
        /** The last day of the billing period. */
        BILLING_PERIOD_END
    }

    /** Where approved exchange rates come from. */
    public enum Provider {
        /** The Finance-maintained {@code currency_exchange_rate} table. */
        DATABASE
    }

    /**
     * Opt-in rate source. Unset (the default) means no provider bean exists and every cross-currency
     * expense fails closed. Setting it never supplies the policy values below - they must be configured too.
     */
    private Provider provider;

    /** Decimal places of the converted amount (at most 2: the billed amount column is DECIMAL(19,2)). */
    private Integer scale;

    private RoundingMode roundingMode;

    private RateDateBasis rateDateBasis;
}
