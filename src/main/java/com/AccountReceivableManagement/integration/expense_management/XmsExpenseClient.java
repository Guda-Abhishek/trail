package com.AccountReceivableManagement.integration.expense_management;

import java.util.List;

/** Read-only HTTP client for the XMS invoice-handoff queue. */
public interface XmsExpenseClient {

    /**
     * Every handed-off event of the PMS project across all pages (bounded by
     * {@code xms.api.max-pages}), from {@code GET /finance/invoice-handoff-queue/handed-off/by-project}
     * with {@code pmsProjectId}, {@code page} and {@code size}. XMS filters by project; AR still verifies
     * every record and filters by billing period. A line item handed off more than once appears once per
     * event. "No handed-off expenses for this project" is a normal, empty outcome - not an error.
     */
    List<XmsHandedOffExpenseDto> fetchHandedOffExpenses(Long pmsProjectId);

    XmsBillingPayloadDto getBillingPayload(String lineItemId);
}
