package com.AccountReceivableManagement.service_Imple.receivable;

import com.AccountReceivableManagement.dto.receivable.ReceivableResponseDto;
import com.AccountReceivableManagement.entity.invoice_generation.Invoice;
import com.AccountReceivableManagement.entity.receivable.Receivable;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceDeliveryStatus;
import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import com.AccountReceivableManagement.entity_enums.receivable.ReceivableStatus;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceDeliveryRepository;
import com.AccountReceivableManagement.repo.invoice_generation.InvoiceRepository;
import com.AccountReceivableManagement.repo.receivable.ReceivableRepository;
import com.AccountReceivableManagement.service_interface.receivable.ReceivableService;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a dispatched Invoice into its single Receivable:
 * lock invoice -> verify dispatched -> reuse existing receivable, or
 * snapshot the invoice's grand total as a new OUTSTANDING receivable.
 * The Invoice is the only source of truth - nothing here looks at the
 * billing type, configuration, schedule, timesheets or tax engine, so Time
 * & Material, Milestone and Recurring invoices are all handled identically.
 * <p>
 * Duplicates are prevented in three layers: the invoice row lock serializes
 * concurrent creations for the same invoice, the existing-receivable check
 * makes repeats idempotent, and {@code uk_receivable_invoice} rejects
 * anything that still slips through.
 * <p>
 * Never sends email and never calls invoice delivery - so it is also the
 * recovery path for an invoice whose SENT delivery was committed but whose
 * receivable is missing.
 */
@Slf4j
@Service
public class ReceivableServiceImpl implements ReceivableService {

    private final InvoiceRepository invoiceRepository;

    private final InvoiceDeliveryRepository invoiceDeliveryRepository;

    private final ReceivableRepository receivableRepository;

    private final TransactionTemplate transactionTemplate;

    private final TransactionTemplate readOnlyTransactionTemplate;

    public ReceivableServiceImpl(InvoiceRepository invoiceRepository,
            InvoiceDeliveryRepository invoiceDeliveryRepository,
            ReceivableRepository receivableRepository,
            PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceDeliveryRepository = invoiceDeliveryRepository;
        this.receivableRepository = receivableRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    /**
     * Runs in a transaction (joining the caller's, if any). A standalone call
     * that still loses an insert race on {@code uk_receivable_invoice} is
     * retried once in a new transaction, where the committed receivable is
     * found and returned. Inside a caller's transaction the race cannot be
     * resolved (that transaction is already rollback-only), so it surfaces
     * as a clean duplicate error instead of a raw SQL one.
     */
    @Override
    public ReceivableResponseDto createReceivableFromInvoice(UUID invoiceId) {

        boolean joinsCallerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return transactionTemplate.execute(status -> createOrGetReceivable(invoiceId));
        } catch (DataIntegrityViolationException ex) {

            if (!isDuplicateInvoiceReceivable(ex)) {
                throw ex;
            }

            if (joinsCallerTransaction) {
                log.warn("Duplicate receivable creation rejected by uk_receivable_invoice for invoice {}",
                        invoiceId);
                throw new GlobalExceptionHandler.DuplicateResourceException(
                        "A receivable has already been created for this invoice."
                );
            }

            log.warn("Receivable insert for invoice {} lost a concurrent race; using the existing receivable",
                    invoiceId);
            return transactionTemplate.execute(status -> createOrGetReceivable(invoiceId));
        }
    }

    private ReceivableResponseDto createOrGetReceivable(UUID invoiceId) {

        Invoice invoice =
                invoiceRepository.findByIdForUpdate(invoiceId)
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "Invoice could not be found."
                        ));

        validateInvoiceDispatched(invoice);

        Optional<Receivable> existing = receivableRepository.findByInvoiceId(invoiceId);
        if (existing.isPresent()) {
            log.info("Receivable {} already exists for invoice {} - no new receivable created",
                    existing.get().getReceivableId(), invoice.getInvoiceNumber());
            return mapToResponse(existing.get());
        }

        validateInvoiceData(invoice);

        Receivable receivable =
                Receivable.builder()
                        .invoice(invoice)
                        .invoiceNumber(invoice.getInvoiceNumber())
                        .clientId(invoice.getClientId())
                        .clientName(invoice.getClientName())
                        .projectId(invoice.getProjectId())
                        .projectName(invoice.getProjectName())
                        .currencyCode(invoice.getCurrencyCode())
                        .invoiceDate(invoice.getInvoiceDate())
                        .dueDate(invoice.getDueDate())
                        .invoiceAmount(invoice.getGrandTotal())
                        .outstandingBalance(invoice.getGrandTotal())
                        .status(ReceivableStatus.OUTSTANDING)
                        .build();

        // Flushed here so a uk_receivable_invoice violation is raised inside
        // the transaction callback (and handled by the caller above) rather
        // than at commit.
        Receivable saved = receivableRepository.saveAndFlush(receivable);

        log.info("Receivable {} created for dispatched invoice {} - outstanding balance {} {}",
                saved.getReceivableId(), saved.getInvoiceNumber(),
                saved.getCurrencyCode(), saved.getOutstandingBalance());

        return mapToResponse(saved);
    }

    @Override
    public List<ReceivableResponseDto> getOutstandingReceivables(
            UUID clientId,
            Long projectId,
            ReceivableStatus status
    ) {

        LocalDate today = LocalDate.now();

        return readOnlyTransactionTemplate.execute(txStatus ->
                receivableRepository.findOutstanding(clientId, projectId, status)
                        .stream()
                        .map(receivable -> mapToResponse(receivable, today))
                        .toList()
        );
    }

    @Override
    public ReceivableResponseDto getReceivableById(UUID receivableId) {

        return readOnlyTransactionTemplate.execute(txStatus ->
                mapToResponse(
                        receivableRepository.findById(receivableId)
                                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                        "Receivable could not be found."
                                ))
                )
        );
    }

    /**
     * "Dispatched" in this system means the invoice is APPROVED (a terminal,
     * immutable status) and at least one "Send to Client" attempt reached
     * SENT - {@code Invoice.status} itself never records delivery.
     */
    private void validateInvoiceDispatched(Invoice invoice) {

        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            throw new GlobalExceptionHandler.ValidationException(
                    "A receivable can only be created for an approved invoice that has been sent to the client."
            );
        }

        if (!invoiceDeliveryRepository.existsByInvoiceIdAndStatus(
                invoice.getInvoiceId(), InvoiceDeliveryStatus.SENT)) {
            throw new GlobalExceptionHandler.ValidationException(
                    "A receivable cannot be created because this invoice has not been sent to the client."
            );
        }
    }

    /**
     * Zero is allowed: invoice generation and delivery both accept a zero
     * grand total, and no zero-value invoice policy exists to contradict.
     */
    private void validateInvoiceData(Invoice invoice) {

        if (invoice.getGrandTotal() == null || invoice.getGrandTotal().signum() < 0) {
            throw new GlobalExceptionHandler.ValidationException(
                    "A receivable cannot be created because the invoice grand total is missing or negative."
            );
        }

        if (invoice.getInvoiceNumber() == null || invoice.getInvoiceNumber().isBlank()
                || invoice.getInvoiceDate() == null
                || invoice.getClientId() == null
                || invoice.getProjectId() == null) {
            throw new GlobalExceptionHandler.ValidationException(
                    "A receivable cannot be created because the invoice is missing its number, date, client or project."
            );
        }
    }

    private boolean isDuplicateInvoiceReceivable(DataIntegrityViolationException ex) {

        if (ex.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null) {
            return violation.getConstraintName().toLowerCase(Locale.ROOT)
                    .contains(Receivable.INVOICE_UNIQUE_CONSTRAINT);
        }

        String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        return message != null
                && message.toLowerCase(Locale.ROOT).contains(Receivable.INVOICE_UNIQUE_CONSTRAINT);
    }

    private ReceivableResponseDto mapToResponse(Receivable receivable) {
        return mapToResponse(receivable, LocalDate.now());
    }

    /**
     * {@code today} is passed in so every row of one list response is aged
     * against the same date. Server-local date, matching how
     * {@code Invoice.invoiceDate} itself is assigned at generation.
     */
    private ReceivableResponseDto mapToResponse(Receivable receivable, LocalDate today) {

        boolean hasBalance = receivable.getOutstandingBalance() != null
                && receivable.getOutstandingBalance().signum() > 0;

        Long daysOutstanding = hasBalance && receivable.getInvoiceDate() != null
                ? Math.max(0, ChronoUnit.DAYS.between(receivable.getInvoiceDate(), today))
                : null;

        Long daysOverdue = hasBalance && receivable.getDueDate() != null
                ? Math.max(0, ChronoUnit.DAYS.between(receivable.getDueDate(), today))
                : null;

        return ReceivableResponseDto.builder()
                .receivableId(receivable.getReceivableId())
                .invoiceId(receivable.getInvoice().getInvoiceId())
                .invoiceNumber(receivable.getInvoiceNumber())
                .clientId(receivable.getClientId())
                .clientName(receivable.getClientName())
                .projectId(receivable.getProjectId())
                .projectName(receivable.getProjectName())
                .currencyCode(receivable.getCurrencyCode())
                .invoiceDate(receivable.getInvoiceDate())
                .dueDate(receivable.getDueDate())
                .invoiceAmount(receivable.getInvoiceAmount())
                .outstandingBalance(receivable.getOutstandingBalance())
                .status(receivable.getStatus())
                .daysOutstanding(daysOutstanding)
                .daysOverdue(daysOverdue)
                .createdAt(receivable.getCreatedAt())
                .updatedAt(receivable.getUpdatedAt())
                .build();
    }
}
