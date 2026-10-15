package com.AccountReceivableManagement.integration.expense_management;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls the XMS invoice-handoff queue (GET only). The configured base URL
 * already includes the {@code /xms} context path (like TMS). Pages are fetched
 * sequentially - never concurrently - and bounded by
 * {@code xms.api.max-pages} / {@code xms.api.max-line-items}. Only
 * transient failures (5xx, network) are retried, with exponential
 * backoff; 4xx are permanent and surface immediately. The JWT is never
 * logged.
 */
@Slf4j
@Component
public class XmsExpenseClientImpl implements XmsExpenseClient {

    static final String HANDED_OFF_PATH = "/finance/invoice-handoff-queue/handed-off/by-project";
    /** XMS answers a project with nothing handed off with this message ("No handed-off expenses found for PMS project N"). */
    static final String NO_EXPENSES_MARKER = "no handed-off expenses found";
    static final String PAYLOAD_PATH = "/finance/invoice-handoff-queue/{lineItemId}/billing-payload";
    static final String CORRELATION_HEADER = "X-Correlation-Id";

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient xmsRestClient;
    private final XmsProperties properties;
    private final XmsAuthorizationProvider authorizationProvider;

    public XmsExpenseClientImpl(RestClient xmsRestClient,
                                XmsProperties properties,
                                XmsAuthorizationProvider authorizationProvider) {
        this.xmsRestClient = xmsRestClient;
        this.properties = properties;
        this.authorizationProvider = authorizationProvider;
    }

    @Override
    public List<XmsHandedOffExpenseDto> fetchHandedOffExpenses(Long pmsProjectId) {
        if (pmsProjectId == null) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.BAD_REQUEST, 0,
                    "pmsProjectId is required to query the XMS handed-off queue.");
        }
        String correlationId = UUID.randomUUID().toString();
        int size = properties.effectivePageSize();
        int page = properties.getFirstPageIndex();
        List<XmsHandedOffExpenseDto> all = new ArrayList<>();

        for (int fetched = 0; fetched < properties.getMaxPages(); fetched++) {
            final int requestedPage = page;
            String body;
            try {
                body = executeWithRetry(correlationId, "handed-off/by-project", () ->
                    xmsRestClient.get()
                            .uri(uri -> uri.path(HANDED_OFF_PATH)
                                    .queryParam("pmsProjectId", pmsProjectId)
                                    .queryParam("page", requestedPage)
                                    .queryParam("size", size)
                                    .build())
                            .headers(h -> addHeaders(h, correlationId))
                            .retrieve()
                            .body(String.class));
            } catch (NoHandedOffExpenses none) {
                log.info("[XmsHandedOffExpenses] correlationId={} pmsProjectId={} XMS reports nothing handed off",
                        correlationId, pmsProjectId);
                return all;
            }

            XmsPageDto data = parseHandedOffPage(body);
            if (data.getContent() != null) {
                all.addAll(data.getContent());
            }
            if (all.size() > properties.getMaxLineItems()) {
                throw new XmsIntegrationException(XmsIntegrationException.Kind.BAD_REQUEST, 0,
                        "XMS returned more than " + properties.getMaxLineItems()
                                + " handed-off events; raise xms.api.max-line-items.");
            }

            int currentPage = data.getPage() != null ? data.getPage() : requestedPage;
            boolean noMore = data.getContent() == null || data.getContent().isEmpty()
                    || Boolean.TRUE.equals(data.getLast())
                    || (data.getTotalPages() != null
                        && currentPage - properties.getFirstPageIndex() + 1 >= data.getTotalPages())
                    || (data.getLast() == null && data.getTotalPages() == null
                        && data.getContent().size() < size);
            if (noMore) {
                log.info("[XmsHandedOffExpenses] correlationId={} pages={} events={}",
                        correlationId, fetched + 1, all.size());
                return all;
            }
            page = currentPage + 1;
        }

        throw new XmsIntegrationException(XmsIntegrationException.Kind.BAD_REQUEST, 0,
                "XMS handed-off pagination exceeded " + properties.getMaxPages()
                        + " pages; aborting (raise xms.api.max-pages if the list legitimately grew).");
    }

    @Override
    public XmsBillingPayloadDto getBillingPayload(String lineItemId) {
        String correlationId = UUID.randomUUID().toString();
        String body = executeWithRetry(correlationId, "billing-payload lineItemId=" + lineItemId, () ->
                xmsRestClient.get()
                        .uri(PAYLOAD_PATH, lineItemId)
                        .headers(h -> addHeaders(h, correlationId))
                        .retrieve()
                        .body(String.class));
        return parsePayload(body, lineItemId);
    }

    // ---- parsing -----------------------------------------------------

    static XmsPageDto parseHandedOffPage(String body) {
        JsonNode root = readTree(body);
        if (root.has("success") && !root.get("success").asBoolean(true)) {
            String message = root.hasNonNull("message") ? root.get("message").asText() : "";
            if (isNoExpensesMessage(message)) {
                XmsPageDto empty = new XmsPageDto();
                empty.setLast(true);
                return empty;
            }
            throw new XmsIntegrationException(XmsIntegrationException.Kind.BAD_REQUEST, 0,
                    "XMS reported a failure for the handed-off list: " + message);
        }
        JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        try {
            return MAPPER.treeToValue(data, XmsPageDto.class);
        } catch (Exception ex) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.MISCONFIGURED, 0,
                    "XMS handed-off response could not be read: " + ex.getMessage(), ex);
        }
    }

    /** The payload envelope is not confirmed: accepts either {"data":{...}} or the bare object. */
    static XmsBillingPayloadDto parsePayload(String body, String lineItemId) {
        JsonNode root = readTree(body);
        JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        try {
            return MAPPER.treeToValue(data, XmsBillingPayloadDto.class);
        } catch (Exception ex) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.MISCONFIGURED, 0,
                    "XMS billing payload for lineItemId=" + lineItemId + " could not be read: " + ex.getMessage(), ex);
        }
    }

    static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.MISCONFIGURED, 0,
                    "XMS returned an empty response body.");
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.MISCONFIGURED, 0,
                    "XMS response was not valid JSON.", ex);
        }
    }

    // ---- transport ---------------------------------------------------

    private void addHeaders(HttpHeaders headers, String correlationId) {
        headers.set(HttpHeaders.AUTHORIZATION, authorizationProvider.resolveAuthorizationHeader());
        headers.set(CORRELATION_HEADER, correlationId);
    }

    private String executeWithRetry(String correlationId, String what, Supplier<String> call) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new XmsIntegrationException(XmsIntegrationException.Kind.MISCONFIGURED, 0,
                    "xms.api.base-url is not configured.");
        }
        int maxAttempts = Math.max(1, properties.getRetryMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                String result = call.get();
                log.info("[XmsCall] correlationId={} call={} outcome=OK attempt={} bodyChars={}",
                        correlationId, what, attempt, result == null ? 0 : result.length());
                return result;
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status >= 500 && attempt < maxAttempts) {
                    log.warn("[XmsRetry] correlationId={} call={} status={} attempt={}/{}",
                            correlationId, what, status, attempt, maxAttempts);
                    backoff(attempt);
                    continue;
                }
                throw classify(status, what, correlationId, ex);
            } catch (ResourceAccessException ex) {
                if (attempt < maxAttempts) {
                    log.warn("[XmsRetry] correlationId={} call={} network failure attempt={}/{}",
                            correlationId, what, attempt, maxAttempts);
                    backoff(attempt);
                    continue;
                }
                throw new XmsIntegrationException(XmsIntegrationException.Kind.UNAVAILABLE, 0,
                        "XMS is unreachable (" + what + ").", ex);
            }
        }
    }

    private void backoff(int attempt) {
        long delay = properties.getRetryBackoffMs() * (1L << (attempt - 1));
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new XmsIntegrationException(XmsIntegrationException.Kind.UNAVAILABLE, 0,
                    "Interrupted while waiting to retry XMS.", ie);
        }
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String oneLine = body.replaceAll("\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private XmsIntegrationException classify(int status, String what, String correlationId,
                                             RestClientResponseException ex) {
        // Truncated server message only - never request headers or the Authorization value.
        log.warn("[XmsError] correlationId={} call={} status={} responseBody={}", correlationId, what, status,
                abbreviate(ex.getResponseBodyAsString()));
        if (status >= 400 && status < 500 && isNoExpensesMessage(ex.getResponseBodyAsString())) {
            return new NoHandedOffExpenses(status);
        }
        XmsIntegrationException.Kind kind;
        String message;
        switch (status) {
            case 400 -> {
                kind = XmsIntegrationException.Kind.BAD_REQUEST;
                message = "XMS rejected the request (" + what + "): " + ex.getResponseBodyAsString();
            }
            case 401, 403 -> {
                kind = XmsIntegrationException.Kind.UNAUTHORIZED;
                message = "XMS did not accept the supplied credentials (" + status
                        + "); the forwarded user token was not accepted or lacks the role XMS requires for this endpoint.";
            }
            case 404 -> {
                kind = XmsIntegrationException.Kind.NOT_FOUND;
                message = "XMS could not find the expense (" + what
                        + "); it may have been cancelled.";
            }
            case 409 -> {
                kind = XmsIntegrationException.Kind.CONFLICT;
                message = "XMS reports a conflict (" + what + "), e.g. the expense is already handed off.";
            }
            default -> {
                kind = status >= 500 ? XmsIntegrationException.Kind.UNAVAILABLE
                        : XmsIntegrationException.Kind.BAD_REQUEST;
                message = "XMS call failed with status " + status + " (" + what + ").";
            }
        }
        return new XmsIntegrationException(kind, status, message, ex);
    }

    static boolean isNoExpensesMessage(String text) {
        return text != null && text.toLowerCase().contains(NO_EXPENSES_MARKER);
    }

    /** XMS says the project simply has nothing handed off - an empty result, caught inside the client. */
    static final class NoHandedOffExpenses extends XmsIntegrationException {
        NoHandedOffExpenses(int upstreamStatus) {
            super(XmsIntegrationException.Kind.NOT_FOUND, upstreamStatus, "No handed-off expenses found for the project.");
        }
    }
}
