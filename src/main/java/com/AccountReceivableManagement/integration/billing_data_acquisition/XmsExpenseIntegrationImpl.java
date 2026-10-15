package com.AccountReceivableManagement.integration.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.integration.currency.ConvertedAmount;
import com.AccountReceivableManagement.integration.currency.ExpenseCurrencyConverter;
import com.AccountReceivableManagement.integration.expense_management.XmsBillingPayloadDto;
import com.AccountReceivableManagement.integration.expense_management.XmsHandedOffExpenseDto;
import com.AccountReceivableManagement.integration.expense_management.XmsExpenseClient;
import com.AccountReceivableManagement.integration.expense_management.XmsProperties;
import com.AccountReceivableManagement.repo.billing_data_acquisition.BillingSnapshotItemRepository;
import com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Retrieves and validates billable expenses from XMS for one project and
 * billing period, from the handed-off queue.
 *
 * <p>Attribution: XMS is asked for the project's own handed-off list
 * ({@code handed-off/by-project?pmsProjectId=...}), where {@code pmsProjectId} is the project's
 * numeric PMS id (AR's project key). Every returned record is re-verified: it must carry that
 * same {@code pmsProjectId} (and, when XMS supplies it, the project's client) - a record that
 * contradicts the request fails the acquisition. Project code and name are never used. When XMS
 * has nothing handed off for the project, or nothing falls in the billing period, there is simply
 * no expense for this project and period.
 *
 * <p>Events and corrections: one record is one handoff event ({@code syncId}); a
 * line item can be handed off more than once, and a later event is a
 * <em>correction</em>, not a new charge. Each lineItemId yields at most one billed
 * line, taken from its latest event:
 * <ul>
 *   <li>the same event returned again (same syncId, same content) is a harmless repeat;</li>
 *   <li>events that disagree, or cannot be ordered, fail the acquisition;</li>
 *   <li>an expense whose latest event carries an invoiceReference is already invoiced
 *       and is skipped; a correction that follows an invoiced event fails safely
 *       (no credit-note/adjustment behaviour is defined);</li>
 *   <li>an expense already on another live snapshot is never billed again - a
 *       corrected event for it also fails with the reason, and it must be applied by
 *       rebuilding that snapshot through the financial-correction workflow.</li>
 * </ul>
 * Anything selected but internally inconsistent fails the acquisition with a
 * message naming the lineItemId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XmsExpenseIntegrationImpl implements ExpenseIntegration {

    private final XmsExpenseClient xmsExpenseClient;
    private final XmsProperties properties;
    private final ProjectMasterReferenceRepository projectMasterReferenceRepository;
    private final BillingSnapshotItemRepository billingSnapshotItemRepository;
    private final ExpenseCurrencyConverter currencyConverter;

    @Override
    public List<ExpenseLineDto> getBillableExpenses(Long projectId, LocalDate periodStart,
                                                    LocalDate periodEnd, String expectedCurrency) {

        ProjectMasterReference project = projectMasterReferenceRepository.findBypmsProjectId(projectId)
                .orElseThrow(() -> new GlobalExceptionHandler.ValidationException(
                        "Project " + projectId + " was not found; expenses cannot be retrieved."));
        Long pmsProjectId = project.getPmsProjectId();
        if (pmsProjectId == null) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Project " + projectId + " has no PMS project id, so its XMS expenses cannot be retrieved.");
        }
        if (periodStart.isAfter(periodEnd)) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Billing period start " + periodStart + " is after end " + periodEnd + ".");
        }

        List<XmsHandedOffExpenseDto> returned = xmsExpenseClient.fetchHandedOffExpenses(pmsProjectId);
        if (returned == null || returned.isEmpty()) {
            log.info("[XmsExpenseAttribution] projectId={} pmsProjectId={} XMS has no handed-off expenses for the project",
                    projectId, pmsProjectId);
            return List.of();
        }

        // Verify every record belongs to the requested project, then group the events by line item.
        Map<String, List<XmsHandedOffExpenseDto>> eventsByLine = new LinkedHashMap<>();
        for (XmsHandedOffExpenseDto record : returned) {
            requireConsistentIdentity(record, project);
            String lineItemId = trim(record.getLineItemId());
            if (lineItemId == null) {
                throw invalid(null, "a handed-off expense has no lineItemId");
            }
            eventsByLine.computeIfAbsent(lineItemId, k -> new ArrayList<>()).add(record);
        }

        List<ExpenseLineDto> lines = new ArrayList<>();
        // Diagnostic counters only - they do not influence which records are accepted.
        Map<String, Integer> excludedByReason = new TreeMap<>();

        for (Map.Entry<String, List<XmsHandedOffExpenseDto>> entry : eventsByLine.entrySet()) {
            String lineItemId = entry.getKey();
            CurrentEvent current = selectCurrentEvent(lineItemId, entry.getValue());
            if (current.skipReason() != null) {
                excludedByReason.merge(current.skipReason(), 1, Integer::sum);
                log.info("[XmsExpenseExcluded] lineItemId={} projectId={} excluded: {}",
                        lineItemId, projectId, current.skipReason());
                continue;
            }
            XmsHandedOffExpenseDto record = current.event();
            if (record.getExpenseDate() == null) {
                throw invalid(lineItemId, "its handed-off record has no expenseDate");
            }
            if (record.getExpenseDate().isBefore(periodStart) || record.getExpenseDate().isAfter(periodEnd)) {
                excludedByReason.merge("outside the billing period", 1, Integer::sum);
                log.info("[XmsExpenseExcluded] lineItemId={} projectId={} expenseDate={} outside {}..{}",
                        lineItemId, projectId, record.getExpenseDate(), periodStart, periodEnd);
                continue;
            }
            validateAmount(lineItemId, record.getCostBasis(), "handed-off record");
            rejectIfAlreadyBilledElsewhere(lineItemId, record, projectId, periodStart, periodEnd);

            XmsBillingPayloadDto payload = xmsExpenseClient.getBillingPayload(lineItemId);
            lines.add(toLine(record, current.supersededSyncIds(), payload, periodStart, periodEnd, expectedCurrency));
        }

        log.info("[XmsExpenseAttribution] projectId={} pmsProjectId={} period={}..{} xmsEvents={} lineItems={} "
                        + "accepted={} excluded={}",
                projectId, pmsProjectId, periodStart, periodEnd, returned.size(), eventsByLine.size(),
                lines.size(), excludedByReason);
        log.info("[XmsExpenseAcquisition] projectId={} period={}..{} accepted={}",
                projectId, periodStart, periodEnd, lines.size());
        return lines;
    }

    /** The one event a line item is billed from, or why it is skipped. */
    private record CurrentEvent(XmsHandedOffExpenseDto event, List<String> supersededSyncIds, String skipReason) {
        static CurrentEvent use(XmsHandedOffExpenseDto event, List<String> superseded) {
            return new CurrentEvent(event, superseded, null);
        }

        static CurrentEvent skip(String reason) {
            return new CurrentEvent(null, List.of(), reason);
        }
    }

    private CurrentEvent selectCurrentEvent(String lineItemId, List<XmsHandedOffExpenseDto> events) {
        // 1. Repeats of the same handoff event collapse to one; a syncId that changes content is a contract violation.
        Map<String, XmsHandedOffExpenseDto> bySyncId = new LinkedHashMap<>();
        for (XmsHandedOffExpenseDto event : events) {
            String syncId = trim(event.getSyncId());
            if (syncId == null) {
                if (events.size() > 1) {
                    throw invalid(lineItemId, "it was handed off more than once but an event has no syncId, "
                            + "so repeats and corrections cannot be told apart");
                }
                return decide(lineItemId, List.of(event), List.of());
            }
            XmsHandedOffExpenseDto previous = bySyncId.putIfAbsent(syncId, event);
            if (previous != null && !sameContent(previous, event)) {
                throw invalid(lineItemId, "handoff event syncId=" + syncId + " was returned twice with different content");
            }
        }
        List<XmsHandedOffExpenseDto> distinct = new ArrayList<>(bySyncId.values());
        if (distinct.size() == 1) {
            return decide(lineItemId, distinct, List.of());
        }

        // 2. A later handoff of the same line item is a correction: order the events, latest wins.
        if (distinct.stream().anyMatch(e -> e.getHandedOffAt() == null)) {
            throw invalid(lineItemId, "it was handed off " + distinct.size()
                    + " times but an event has no handedOffAt, so the latest cannot be determined");
        }
        distinct.sort(Comparator.comparing(XmsHandedOffExpenseDto::getHandedOffAt));
        XmsHandedOffExpenseDto latest = distinct.get(distinct.size() - 1);
        XmsHandedOffExpenseDto beforeLatest = distinct.get(distinct.size() - 2);
        if (latest.getHandedOffAt().equals(beforeLatest.getHandedOffAt())) {
            throw invalid(lineItemId, "its latest handoff events (syncId=" + beforeLatest.getSyncId() + ", "
                    + latest.getSyncId() + ") share the same handedOffAt, so the correction order is ambiguous");
        }
        List<String> superseded = new ArrayList<>();
        for (int i = 0; i < distinct.size() - 1; i++) {
            superseded.add(distinct.get(i).getSyncId());
        }
        return decide(lineItemId, List.of(latest), superseded, distinct);
    }

    private CurrentEvent decide(String lineItemId, List<XmsHandedOffExpenseDto> single, List<String> superseded) {
        return decide(lineItemId, single, superseded, single);
    }

    private CurrentEvent decide(String lineItemId, List<XmsHandedOffExpenseDto> latestOnly, List<String> superseded,
                                List<XmsHandedOffExpenseDto> allOrdered) {
        XmsHandedOffExpenseDto latest = latestOnly.get(0);
        if (hasText(latest.getInvoiceReference())) {
            return CurrentEvent.skip("already invoiced (invoiceReference=" + latest.getInvoiceReference() + ")");
        }
        for (XmsHandedOffExpenseDto earlier : allOrdered) {
            if (earlier != latest && hasText(earlier.getInvoiceReference())) {
                // The expense was invoiced, then handed off again without a reference. How to bill or
                // reverse that difference (credit note, adjustment) has not been decided - never guess.
                throw invalid(lineItemId, "a correction (syncId=" + latest.getSyncId() + ", remarks='"
                        + latest.getRemarks() + "') was handed off after the expense was invoiced (invoiceReference="
                        + earlier.getInvoiceReference() + "); handling corrections to invoiced expenses is not yet "
                        + "defined, so nothing was billed");
            }
        }
        return CurrentEvent.use(latest, superseded);
    }

    /**
     * True when two records of one handoff event (same syncId) carry the same content. Every field that
     * can change what is billed or why is compared; a difference means XMS contradicted itself.
     */
    private static boolean sameContent(XmsHandedOffExpenseDto a, XmsHandedOffExpenseDto b) {
        return sameDecimal(a.getCostBasis(), b.getCostBasis())
                && sameDecimal(a.getBaseAmount(), b.getBaseAmount())
                && sameDecimal(a.getRecoverableTaxAmount(), b.getRecoverableTaxAmount())
                && java.util.Objects.equals(a.getExpenseDate(), b.getExpenseDate())
                && java.util.Objects.equals(a.getHandedOffAt(), b.getHandedOffAt())
                && java.util.Objects.equals(trim(a.getTaxCode()), trim(b.getTaxCode()))
                && java.util.Objects.equals(trim(a.getInvoiceReference()), trim(b.getInvoiceReference()))
                && java.util.Objects.equals(trim(a.getRemarks()), trim(b.getRemarks()));
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /**
     * Never bills an expense twice: a line item already on another live snapshot is rejected. The message
     * says whether it is a repeat of the same handoff or a correction of an already-billed expense.
     */
    private void rejectIfAlreadyBilledElsewhere(String lineItemId, XmsHandedOffExpenseDto record, Long projectId,
                                                LocalDate periodStart, LocalDate periodEnd) {
        List<BillingSnapshotItem> existing = billingSnapshotItemRepository.findExpenseItemsInOtherSnapshots(
                lineItemId, projectId, periodStart, periodEnd);
        if (existing == null || existing.isEmpty()) {
            return;
        }
        BillingSnapshotItem item = existing.get(0);
        BillingSnapshot snapshot = item.getBillingSnapshot();
        String where = snapshot != null
                ? "snapshot " + snapshot.getSnapshotNumber() + " (" + snapshot.getStatus() + ")" : "another snapshot";
        boolean invoiced = snapshot != null && snapshot.getStatus() == BillingSnapshotStatus.INVOICED;
        String billedEvent = trim(item.getSourceEventId());
        boolean correction = billedEvent != null && !billedEvent.equals(trim(record.getSyncId()));
        if (!correction) {
            throw invalid(lineItemId, "it is already included in another billing snapshot (" + where + ")");
        }
        if (invoiced) {
            throw invalid(lineItemId, "a corrected handoff (syncId=" + record.getSyncId() + ", remarks='"
                    + record.getRemarks() + "') arrived for an expense already billed in " + where
                    + "; correcting an invoiced expense is not yet defined, so nothing was billed");
        }
        throw invalid(lineItemId, "a corrected handoff (syncId=" + record.getSyncId() + ", remarks='"
                + record.getRemarks() + "') arrived for an expense already in " + where
                + "; it is not added as a new charge - apply it by rebuilding that snapshot through the "
                + "financial-correction workflow");
    }

    /**
     * The record must be for the PMS project that was requested. resolvedClientId is checked when XMS
     * supplies it (and the project has a client) - a different client means the record is not this project's.
     */
    private void requireConsistentIdentity(XmsHandedOffExpenseDto record, ProjectMasterReference project) {
        String lineItemId = trim(record.getLineItemId());
        if (record.getPmsProjectId() == null) {
            throw invalid(lineItemId, "its handed-off record has no pmsProjectId");
        }
        if (!record.getPmsProjectId().equals(project.getPmsProjectId())) {
            throw invalid(lineItemId, "it belongs to PMS project " + record.getPmsProjectId()
                    + " but project " + project.getPmsProjectId() + " was requested");
        }
        String resolved = trim(record.getResolvedClientId());
        if (resolved != null && project.getClientId() != null) {
            try {
                if (!UUID.fromString(resolved).equals(project.getClientId())) {
                    throw invalid(lineItemId, "its client " + resolved + " is not the project's client "
                            + project.getClientId());
                }
            } catch (IllegalArgumentException ex) {
                throw invalid(lineItemId, "its resolvedClientId is not a UUID");
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ExpenseLineDto toLine(XmsHandedOffExpenseDto record, List<String> supersededSyncIds,
                                  XmsBillingPayloadDto payload, LocalDate periodStart,
                                  LocalDate periodEnd, String expectedCurrency) {

        String lineItemId = record.getLineItemId();
        if (payload == null) {
            throw invalid(lineItemId, "XMS returned no billing payload");
        }
        if (!lineItemId.equals(payload.getLineItemId())) {
            throw invalid(lineItemId, "the billing payload belongs to lineItemId=" + payload.getLineItemId());
        }
        if (payload.getCostBasis() == null) {
            throw invalid(lineItemId, "the billing payload has no costBasis");
        }
        validateAmount(lineItemId, payload.getCostBasis(), "billing payload");
        if (record.getCostBasis() != null && record.getCostBasis().compareTo(payload.getCostBasis()) != 0) {
            throw invalid(lineItemId, "costBasis differs between the handed-off list ("
                    + record.getCostBasis() + ") and the billing payload (" + payload.getCostBasis() + ")");
        }
        if (payload.getExpenseDate() == null) {
            throw invalid(lineItemId, "the billing payload has no expenseDate");
        }
        validateDate(lineItemId, payload.getExpenseDate(), periodStart, periodEnd);
        validateCurrency(lineItemId, payload.getCurrency());
        if (record.getBaseCurrencyCode() != null && !record.getBaseCurrencyCode().isBlank()
                && !record.getBaseCurrencyCode().equalsIgnoreCase(payload.getCurrency())) {
            throw invalid(lineItemId, "baseCurrencyCode " + record.getBaseCurrencyCode()
                    + " differs from the billing payload currency " + payload.getCurrency());
        }

        if (payload.getInvoiceReference() != null && !payload.getInvoiceReference().isBlank()) {
            throw invalid(lineItemId, "it already carries invoiceReference=" + payload.getInvoiceReference()
                    + ", which contradicts the handed-off list, which reported it as not invoiced");
        }

        // Same currency: amount and currency pass through untouched and no rate is
        // looked up. Different currency: converted once, here, before totals exist.
        ConvertedAmount billed;
        try {
            billed = currencyConverter.convert(payload.getCostBasis(), payload.getCurrency(),
                    expectedCurrency != null && !expectedCurrency.isBlank() ? expectedCurrency : payload.getCurrency(),
                    payload.getExpenseDate(), periodEnd);
        } catch (GlobalExceptionHandler.ValidationException ex) {
            throw invalid(lineItemId, ex.getMessage().replaceAll("[.]$", ""));
        }
        if (billed.converted()) {
            log.info("[XmsExpenseConverted] lineItemId={} {} {} -> {} {} rate={} rateDate={} rateSource={}",
                    lineItemId, billed.sourceAmount(), billed.sourceCurrency(), billed.amount(),
                    billed.currency(), billed.rate(), billed.rateEffectiveDate(), billed.rateSourceReference());
        }

        return ExpenseLineDto.builder()
                .lineItemId(lineItemId)
                .syncId(trim(record.getSyncId()))
                .handedOffAt(record.getHandedOffAt())
                .handoffRemarks(record.getRemarks())
                .supersededSyncIds(new ArrayList<>(supersededSyncIds))
                .payloadVersion(payload.getPayloadVersion())
                .reportNumber(firstNonBlank(payload.getReportNumber(), record.getReportNumber()))
                .employeeId(firstNonBlank(payload.getEmployeeId(), record.getEmployeeId()))
                .expenseDate(payload.getExpenseDate())
                .category(firstNonBlank(payload.getCategory(), record.getCategoryName()))
                .description(firstNonBlank(payload.getDescription(), record.getDescription()))
                .merchantName(payload.getMerchantName())
                .projectCode(firstNonBlank(payload.getProjectCode(), record.getProjectCode()))
                .projectName(firstNonBlank(payload.getProjectName(), record.getProjectName()))
                .clientName(firstNonBlank(payload.getClientName(), record.getResolvedClientName()))
                .costBasis(billed.amount())
                .currency(billed.converted() ? billed.currency() : payload.getCurrency())
                .sourceAmount(billed.converted() ? billed.sourceAmount() : null)
                .sourceCurrency(billed.converted() ? billed.sourceCurrency() : null)
                .exchangeRate(billed.rate())
                .exchangeRateDate(billed.rateEffectiveDate())
                .exchangeRateSource(billed.rateSourceReference())
                .grossAmount(payload.getGrossAmount())
                .inputTaxAmount(payload.getInputTaxAmount())
                .recoverableTaxAmount(payload.getRecoverableTaxAmount())
                .taxCode(payload.getTaxCode())
                .originalCurrency(payload.getOriginalCurrency())
                .originalAmount(payload.getOriginalAmount())
                .receiptIds(payload.getReceiptIds() != null ? payload.getReceiptIds() : new ArrayList<>())
                .build();
    }

    private void validateAmount(String lineItemId, BigDecimal costBasis, String source) {
        if (costBasis == null) {
            throw invalid(lineItemId, "the " + source + " has no costBasis");
        }
        if (costBasis.signum() < 0) {
            throw invalid(lineItemId, "costBasis is negative (" + costBasis + ") in the " + source);
        }
    }

    private void validateDate(String lineItemId, LocalDate expenseDate,
                              LocalDate periodStart, LocalDate periodEnd) {
        if (expenseDate != null && (expenseDate.isBefore(periodStart) || expenseDate.isAfter(periodEnd))) {
            throw invalid(lineItemId, "expenseDate " + expenseDate + " is outside the billing period "
                    + periodStart + " to " + periodEnd);
        }
    }

    /** Trimmed text, or null when the value is null or blank. */
    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateCurrency(String lineItemId, String currency) {
        if (currency == null || currency.isBlank()) {
            throw invalid(lineItemId, "the billing payload has no currency");
        }
        String base = properties.getBaseCurrency();
        if (base != null && !base.isBlank() && !base.equalsIgnoreCase(currency)) {
            throw invalid(lineItemId, "currency " + currency + " is not the organisation base currency " + base);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private GlobalExceptionHandler.ValidationException invalid(String lineItemId, String reason) {
        log.warn("[XmsExpenseRejected] lineItemId={} reason={}", lineItemId, reason);
        return new GlobalExceptionHandler.ValidationException(
                "XMS expense " + (lineItemId != null ? "[" + lineItemId + "] " : "") + "rejected: " + reason + ".");
    }
}
