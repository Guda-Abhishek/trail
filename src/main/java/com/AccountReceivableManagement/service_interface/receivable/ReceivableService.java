package com.AccountReceivableManagement.service_interface.receivable;

import com.AccountReceivableManagement.dto.receivable.ReceivableResponseDto;
import com.AccountReceivableManagement.entity_enums.receivable.ReceivableStatus;

import java.util.List;
import java.util.UUID;

public interface ReceivableService {

    /**
     * Creates the single receivable for a dispatched invoice, or returns the
     * existing one if it was already created - safe to call repeatedly for
     * the same invoice (resend, client retry, concurrent send), and safe to
     * call for an already-dispatched invoice whose receivable is missing.
     * <p>
     * The invoice is eligible only when it is {@code APPROVED} and has at
     * least one {@code SENT} {@code InvoiceDelivery}. Its grand total must
     * be present and not negative; the receivable's invoice amount and
     * opening outstanding balance are both that grand total, and its status
     * is {@code OUTSTANDING}.
     * <p>
     * This is also the recovery operation for "SENT delivery but receivable
     * missing" (e.g. receivable creation failed after the email went out):
     * it never sends email, so repairing a receivable never re-sends the
     * invoice.
     * <p>
     * System-driven only - invoked after invoice dispatch commits, never
     * exposed as a user action. Joins the caller's transaction if one is
     * active, otherwise runs in its own. The invoice row is locked
     * ({@code SELECT ... FOR UPDATE}) before the existing-receivable check;
     * a caller that already read data in the same transaction must have
     * taken that same lock as its first read, or MySQL's repeatable-read
     * snapshot can hide a receivable committed by a concurrent request.
     */
    ReceivableResponseDto createReceivableFromInvoice(UUID invoiceId);

    /**
     * Receivables with a balance still owed ({@code outstandingBalance > 0}),
     * read as currently committed - so any later balance change is reflected
     * on the next call. Zero-balance receivables are excluded whatever their
     * status, but never deleted. Every filter is optional and only narrows
     * the result.
     */
    List<ReceivableResponseDto> getOutstandingReceivables(
            UUID clientId,
            Long projectId,
            ReceivableStatus status
    );

    /**
     * One receivable regardless of balance or status, including settled
     * history - its latest committed state.
     */
    ReceivableResponseDto getReceivableById(UUID receivableId);
}
