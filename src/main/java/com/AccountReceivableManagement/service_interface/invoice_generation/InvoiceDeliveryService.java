package com.AccountReceivableManagement.service_interface.invoice_generation;

import com.AccountReceivableManagement.dto.invoice_generation.InvoiceDeliveryResponseDto;

import java.util.List;
import java.util.UUID;

public interface InvoiceDeliveryService {

    /**
     * Sends the given invoice to its client by email, with the generated
     * invoice PDF attached. Requires the invoice to be {@code APPROVED},
     * to have a non-blank recipient email, and an active
     * {@code CompanyProfile} to exist - each precondition failure throws
     * before any {@code InvoiceDelivery} row is created. Once those
     * preconditions pass, a {@code PENDING} row is persisted first, and the
     * attempt's outcome ({@code SENT} or {@code FAILED}) is always
     * returned to the caller - a failed send never throws past this point
     * and never leaves the delivery state ambiguous. {@link
     * com.AccountReceivableManagement.entity.invoice_generation.Invoice#getStatus()}
     * is never modified by this method. Once a {@code SENT} outcome has
     * committed, the invoice's single {@code Receivable} is created in its
     * own transaction (or reused on a resend); a failure there is logged and
     * leaves the SENT record intact for recovery. A {@code FAILED} outcome
     * creates no receivable.
     */
    InvoiceDeliveryResponseDto sendInvoice(UUID invoiceId);

    /**
     * Every delivery attempt for one invoice, newest first. The first
     * entry is the current delivery state.
     */
    List<InvoiceDeliveryResponseDto> getDeliveryHistory(UUID invoiceId);
}
