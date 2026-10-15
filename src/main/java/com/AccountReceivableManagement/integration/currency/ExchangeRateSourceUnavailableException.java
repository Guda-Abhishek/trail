package com.AccountReceivableManagement.integration.currency;

/**
 * The approved exchange-rate source could not be read (for example a database failure). Distinct from
 * "no approved rate" - which is an empty lookup and a validation failure. The cause is kept for server
 * logs only; {@link #PUBLIC_MESSAGE} is the only text a caller is ever shown, so no SQL, host name or
 * other infrastructure detail reaches the frontend.
 */
public class ExchangeRateSourceUnavailableException extends RuntimeException {

    public static final String PUBLIC_MESSAGE =
            "The exchange-rate source is currently unavailable, so the expense could not be converted. "
                    + "Nothing was saved; please try again later.";

    public ExchangeRateSourceUnavailableException(Throwable cause) {
        super(PUBLIC_MESSAGE, cause);
    }
}
