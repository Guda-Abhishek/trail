package com.AccountReceivableManagement.service_Imple.invoice_generation;

import com.AccountReceivableManagement.dto.invoice_generation.InvoiceDeliveryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceResponseDto;
import com.AccountReceivableManagement.entity.invoice_generation.Invoice;
import com.AccountReceivableManagement.entity.invoice_generation.InvoiceDelivery;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceDeliveryStatus;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.client.ClientRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceDeliveryRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceRepository;
import com.AccountReceivableManagement.service_Imple.email.EmailDeliveryException;
import com.AccountReceivableManagement.service_interface.email.EmailService;
import com.AccountReceivableManagement.service_interface.invoice_generation.InvoiceDeliveryService;
import com.AccountReceivableManagement.service_interface.invoice_generation.InvoiceDocumentService;
import com.AccountReceivableManagement.service_interface.invoice_generation.InvoiceService;
import com.AccountReceivableManagement.service_interface.receivable.ReceivableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates one "Send to Client" attempt:
 * Approved Invoice -> validate recipient -> generate PDF -> create PENDING
 * delivery row -> send through Gmail SMTP -> persist SENT/FAILED.
 * Never marks {@link Invoice#getStatus()} as sent - delivery state lives
 * entirely on {@link InvoiceDelivery}, a separate audit trail, matching
 * the same standalone-history convention already used by
 * {@code InvoiceApprovalHistory}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class InvoiceDeliveryServiceImpl implements InvoiceDeliveryService {

    private final InvoiceRepository invoiceRepository;

    private final ClientRepository clientRepository;

    private final InvoiceDeliveryRepository invoiceDeliveryRepository;

    private final InvoiceService invoiceService;

    private final InvoiceDocumentService invoiceDocumentService;

    private final EmailService emailService;

    private final ReceivableService receivableService;

    private final PlatformTransactionManager transactionManager;

    @Override
    public InvoiceDeliveryResponseDto sendInvoice(UUID invoiceId) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "Invoice could not be found."
                        ));

                String recipientEmail = resolveRecipientEmail(invoice);
                validateInvoiceReadyForDelivery(invoice, recipientEmail);

        InvoiceDelivery delivery =
                InvoiceDelivery.builder()
                        .invoiceId(invoice.getInvoiceId())
                        .recipientEmail(recipientEmail)
                        .status(InvoiceDeliveryStatus.PENDING)
                        .retryCount(0)
                        .build();

        delivery = invoiceDeliveryRepository.save(delivery);

        InvoiceResponseDto invoiceResponse = invoiceService.getInvoiceById(invoiceId);

        byte[] pdfBytes;
        try {
            pdfBytes = invoiceDocumentService.generateInvoicePdf(invoiceResponse);
        } catch (PdfGenerationException ex) {
            return markFailed(delivery, "PDF generation failed: " + ex.getMessage());
        }

        String subject = buildSubject(invoiceResponse);
        String body = buildBody(invoiceResponse);
        String attachmentFilename = "invoice-" + invoiceResponse.getInvoiceNumber() + ".pdf";

        String messageId;
        try {
            messageId = emailService.sendEmailWithAttachment(
                    recipientEmail,
                    subject,
                    body,
                    pdfBytes,
                    attachmentFilename,
                    "application/pdf"
            );
        } catch (EmailDeliveryException ex) {
            return markFailed(delivery, ex.getMessage());
        }

        delivery.setStatus(InvoiceDeliveryStatus.SENT);
        delivery.setSentAt(LocalDateTime.now());
        delivery.setProviderMessageId(messageId);

        InvoiceDelivery saved = invoiceDeliveryRepository.save(delivery);

        createReceivableAfterCommit(invoiceId, invoiceResponse.getInvoiceNumber());

        return mapToResponse(saved, invoiceResponse.getInvoiceNumber());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceDeliveryResponseDto> getDeliveryHistory(UUID invoiceId) {

        Invoice invoice =
                invoiceRepository.findById(invoiceId)
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "Invoice could not be found."
                        ));

        return invoiceDeliveryRepository
                .findByInvoiceIdOrderByCreatedAtDesc(invoiceId)
                .stream()
                .map(delivery -> mapToResponse(delivery, invoice.getInvoiceNumber()))
                .toList();
    }

    /**
     * Creates the invoice's receivable once the SENT delivery has committed,
     * in its own transaction. The email has already left by then, so a
     * receivable failure must not roll back the SENT record: keeping it is
     * what lets the missing receivable be recovered later through
     * {@link ReceivableService#createReceivableFromInvoice} without sending
     * the invoice again. REQUIRES_NEW is needed because data access inside
     * afterCommit would otherwise join the already-committed transaction.
     */
    private void createReceivableAfterCommit(UUID invoiceId, String invoiceNumber) {

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                try {
                    requiresNew.executeWithoutResult(status ->
                            receivableService.createReceivableFromInvoice(invoiceId));
                } catch (RuntimeException ex) {
                    log.error("Invoice {} ({}) was sent but its receivable could not be created; "
                                    + "recover with ReceivableService.createReceivableFromInvoice - do not resend",
                            invoiceNumber, invoiceId, ex);
                }
            }
        });
    }

    private String resolveRecipientEmail(Invoice invoice) {
        if (invoice.getEmail() != null && !invoice.getEmail().isBlank()) {
            return invoice.getEmail();
        }

        if (invoice.getClientId() == null || clientRepository == null) {
            return null;
        }

        return clientRepository.findById(invoice.getClientId())
                .map(client -> client.getEmail())
                .orElse(null);
    }

    private void validateInvoiceReadyForDelivery(
            Invoice invoice,
            String recipientEmail
    ) {

        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Invoice must be approved before it can be sent to the client."
            );
        }

        if (invoice.getInvoiceNumber() == null || invoice.getInvoiceNumber().isBlank()
                || invoice.getInvoiceDate() == null
                || invoice.getSubtotal() == null
                || invoice.getTotalTaxAmount() == null
                || invoice.getGrandTotal() == null
                || invoice.getGrandTotal().signum() < 0) {

            throw new GlobalExceptionHandler.ValidationException(
                    "Invoice does not have valid invoice data required for delivery."
            );
        }

        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Client email is not configured for this invoice."
            );
        }
    }

    /**
     * Persists the FAILED outcome and returns it to the caller - never
     * rethrows, so the API always reports what actually happened instead
     * of surfacing a generic 500 that would hide the delivery record.
     */
    private InvoiceDeliveryResponseDto markFailed(InvoiceDelivery delivery, String reason) {

        delivery.setStatus(InvoiceDeliveryStatus.FAILED);
        delivery.setFailedAt(LocalDateTime.now());
        delivery.setFailureReason(reason);

        InvoiceDelivery saved = invoiceDeliveryRepository.save(delivery);

        log.warn("Invoice delivery {} failed: {}", delivery.getInvoiceDeliveryId(), reason);

        Invoice invoice = invoiceRepository.findById(delivery.getInvoiceId()).orElse(null);

        return mapToResponse(saved, invoice != null ? invoice.getInvoiceNumber() : null);
    }

    private String buildSubject(InvoiceResponseDto invoice) {
        return "Invoice " + invoice.getInvoiceNumber() + " - " + invoice.getSellerLegalName();
    }

    private String buildBody(InvoiceResponseDto invoice) {

        String clientGreeting =
                invoice.getClientName() != null && !invoice.getClientName().isBlank()
                        ? invoice.getClientName()
                        : "Customer";

        String amount =
                (invoice.getCurrencyCode() != null ? invoice.getCurrencyCode() + " " : "")
                        + invoice.getGrandTotal();

        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(clientGreeting).append(",\n\n");
        body.append("Please find attached invoice ").append(invoice.getInvoiceNumber())
                .append(" for ").append(amount).append(".\n\n");
        body.append("Invoice date: ").append(invoice.getInvoiceDate()).append("\n");
        if (invoice.getDueDate() != null) {
            body.append("Due date: ").append(invoice.getDueDate()).append("\n");
        }
        body.append("\nThe invoice document is attached to this email as a PDF.\n\n");
        body.append("Regards,\n");
        body.append(invoice.getSellerLegalName());

        return body.toString();
    }

    private InvoiceDeliveryResponseDto mapToResponse(InvoiceDelivery delivery, String invoiceNumber) {

        return InvoiceDeliveryResponseDto.builder()
                .deliveryId(delivery.getInvoiceDeliveryId())
                .invoiceId(delivery.getInvoiceId())
                .invoiceNumber(invoiceNumber)
                .recipientEmail(delivery.getRecipientEmail())
                .deliveryStatus(delivery.getStatus())
                .sentAt(delivery.getSentAt())
                .failedAt(delivery.getFailedAt())
                .failureReason(delivery.getFailureReason())
                .createdAt(delivery.getCreatedAt())
                .build();
    }
}
