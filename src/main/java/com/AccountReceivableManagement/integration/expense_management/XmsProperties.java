package com.AccountReceivableManagement.integration.expense_management;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the Expense Management (XMS) integration
 * ({@code xms.api.*}). Nothing here has a hardcoded URL, token or secret;
 * see application.properties and .env.example.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "xms.api")
public class XmsProperties {

    public static final int MAX_PAGE_SIZE = 100;

    /** Master switch. When false, expense acquisition is skipped (existing billing behaviour). */
    private boolean enabled = false;

    private String baseUrl;

    private int connectTimeoutMs = 5000;

    private int readTimeoutMs = 15000;

    /** Requested page size; clamped to 1..{@link #MAX_PAGE_SIZE}. */
    private int pageSize = 10;

    /** Index of the first page ({@code page} query parameter). */
    private int firstPageIndex = 0;

    /** Hard stop on pages fetched per acquisition - prevents unbounded loops. */
    private int maxPages = 100;

    /** Hard stop on expense lines accepted per acquisition. */
    private int maxLineItems = 1000;

    /**
     * Optional organisation base currency. When set, every expense currency
     * must equal it (in addition to matching the billing configuration currency).
     */
    private String baseCurrency;

    /** Total attempts per GET (1 = no retry) - transient failures only. */
    private int retryMaxAttempts = 3;

    /** Initial backoff; doubles on each further attempt. */
    private long retryBackoffMs = 500;

    public int effectivePageSize() {
        return Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
    }
}
