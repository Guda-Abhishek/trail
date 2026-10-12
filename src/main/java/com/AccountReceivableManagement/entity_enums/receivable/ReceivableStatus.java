package com.AccountReceivableManagement.entity_enums.receivable;

/**
 * Collection state of one {@code Receivable}. Every receivable starts as
 * {@code OUTSTANDING} when its invoice is dispatched; nothing assigns
 * {@code PARTIALLY_PAID} or {@code SETTLED} yet - the formal lifecycle
 * (incl. Paid/Closed and the overdue flag) belongs to a later story and may
 * reshape these values. Whether a receivable is outstanding is decided by
 * its balance, not by this status.
 */
public enum ReceivableStatus {
    OUTSTANDING,
    PARTIALLY_PAID,
    SETTLED
}
