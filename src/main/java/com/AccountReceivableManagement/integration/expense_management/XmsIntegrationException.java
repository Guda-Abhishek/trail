package com.AccountReceivableManagement.integration.expense_management;

import lombok.Getter;

/**
 * A failed XMS call, classified so callers (and the global handler) never
 * have to interpret raw HTTP statuses.
 */
@Getter
public class XmsIntegrationException extends RuntimeException {

    public enum Kind {
        /** 400 - request or business validation rejected by XMS. */
        BAD_REQUEST,
        /** 401/403 - the credentials AR presented were not accepted by XMS. */
        UNAUTHORIZED,
        /** 404 - line item not found (stale data or cancelled upstream). */
        NOT_FOUND,
        /** 409 - already handed off / conflict. */
        CONFLICT,
        /** 5xx / network failure, after bounded retries. */
        UNAVAILABLE,
        /** Missing configuration, or an unreadable response. */
        MISCONFIGURED
    }

    private final Kind kind;
    private final int upstreamStatus;

    public XmsIntegrationException(Kind kind, int upstreamStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
    }

    public XmsIntegrationException(Kind kind, int upstreamStatus, String message) {
        this(kind, upstreamStatus, message, null);
    }
}
