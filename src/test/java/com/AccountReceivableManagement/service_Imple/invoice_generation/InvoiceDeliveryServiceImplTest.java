package com.AccountReceivableManagement.service_Imple.invoice_generation;

import com.AccountReceivableManagement.dto.invoice_generation.InvoiceDeliveryResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceResponseDto;
import com.AccountReceivableManagement.entity.client_entity.Client;
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
import com.AccountReceivableManagement.service_interface.invoice_generation.InvoiceDocumentService;
import com.AccountReceivableManagement.service_interface.invoice_generation.InvoiceService;
import com.AccountReceivableManagement.service_interface.receivable.ReceivableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceDeliveryServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

        @Mock
        private ClientRepository clientRepository;

    @Mock
    private InvoiceDeliveryRepository invoiceDeliveryRepository;

    @Mock
    private InvoiceService invoiceService;

    @Mock
    private InvoiceDocumentService invoiceDocumentService;

    @Mock
    private EmailService emailService;

    @Mock
    private ReceivableService receivableService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private InvoiceDeliveryServiceImpl invoiceDeliveryService;

    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        // sendInvoice registers an after-commit callback; stand in for the
        // @Transactional boundary that is absent in a plain unit test.
        TransactionSynchronizationManager.initSynchronization();
        invoiceId = UUID.randomUUID();
        lenient().when(invoiceDeliveryRepository.save(any(InvoiceDelivery.class)))
                .thenAnswer(invocation -> {
                    InvoiceDelivery delivery = invocation.getArgument(0);
                    if (delivery.getInvoiceDeliveryId() == null) {
                        delivery.setInvoiceDeliveryId(UUID.randomUUID());
                    }
                    return delivery;
                });
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private Invoice approvedInvoice() {
        return Invoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber("INV-20260908164549")
                .status(InvoiceStatus.APPROVED)
                .invoiceDate(LocalDate.of(2026, 9, 8))
                .dueDate(LocalDate.of(2026, 10, 8))
                .clientName("Account Management")
                .email("client@example.com")
                .currencyCode("USD")
                .subtotal(new BigDecimal("5500.00"))
                .totalTaxAmount(new BigDecimal("990.00"))
                .grandTotal(new BigDecimal("6490.00"))
                // Point-in-time seller snapshot, as frozen onto the Invoice
                // at generation time - delivery must use this, never a live
                // Company Profile lookup.
                .sellerLegalName("Example Global Infotech Private Limited")
                .sellerEmail("billing@example.com")
                .sellerPhone("+91 40 1234 5678")
                .build();
    }

    private InvoiceResponseDto invoiceResponse(Invoice invoice) {
        return InvoiceResponseDto.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .clientName(invoice.getClientName())
                .email(invoice.getEmail())
                .currencyCode(invoice.getCurrencyCode())
                .invoiceDate(invoice.getInvoiceDate())
                .dueDate(invoice.getDueDate())
                .subtotal(invoice.getSubtotal())
                .totalTaxAmount(invoice.getTotalTaxAmount())
                .grandTotal(invoice.getGrandTotal())
                .sellerLegalName(invoice.getSellerLegalName())
                .sellerEmail(invoice.getSellerEmail())
                .sellerPhone(invoice.getSellerPhone())
                .items(List.of())
                .taxComponents(List.of())
                .build();
    }

    // 1. Successful email delivery -> 8. Delivery status becomes SENT after successful email.
    // 9. Uses the Invoice's own seller snapshot for the PDF/email content -
    // no CompanyProfileService dependency exists on this class at all.
    @Test
    void sendInvoice_approvedInvoiceWithEmail_sendsAndMarksSent() {
        Invoice invoice = approvedInvoice();
        InvoiceResponseDto response = invoiceResponse(invoice);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceService.getInvoiceById(invoiceId)).thenReturn(response);
        when(invoiceDocumentService.generateInvoicePdf(response))
                .thenReturn(new byte[]{1, 2, 3});
        when(emailService.sendEmailWithAttachment(
                eq("client@example.com"), anyString(), anyString(), any(byte[].class), anyString(), anyString()
        )).thenReturn("<message-id@gmail.com>");

        InvoiceDeliveryResponseDto result = invoiceDeliveryService.sendInvoice(invoiceId);

        assertThat(result.getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.SENT);
        // 10. Recipient still comes from the Invoice's existing client
        // email field (CDC-derived), never from the seller snapshot.
        assertThat(result.getRecipientEmail()).isEqualTo("client@example.com");
        assertThat(result.getSentAt()).isNotNull();
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getInvoiceNumber()).isEqualTo("INV-20260908164549");

        // Invoice status itself is never touched by delivery.
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.APPROVED);
        verify(invoiceRepository, never()).save(any());

        // A SENT outcome creates the invoice's receivable - only once the
        // SENT delivery has committed.
        verifyNoInteractions(receivableService);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(receivableService).createReceivableFromInvoice(invoiceId);

        ArgumentCaptor<InvoiceDelivery> captor = ArgumentCaptor.forClass(InvoiceDelivery.class);
        verify(invoiceDeliveryRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(InvoiceDeliveryStatus.SENT);
        assertThat(captor.getValue().getProviderMessageId()).isEqualTo("<message-id@gmail.com>");

        // Email subject/body are built from the invoice's own seller
        // snapshot - verified via the actual subject argument sent.
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailWithAttachment(
                anyString(), subjectCaptor.capture(), anyString(), any(byte[].class), anyString(), anyString()
        );
        assertThat(subjectCaptor.getValue()).contains("Example Global Infotech Private Limited");
    }

    // 2. Invoice not found.
    @Test
    void sendInvoice_invoiceNotFound_throwsResourceNotFoundException() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceDeliveryService.sendInvoice(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class);

        verifyNoInteractions(invoiceDeliveryRepository, emailService, invoiceDocumentService);
    }

    @Test
    void sendInvoice_legacyInvoice_resolvesEmailFromClient() {
        UUID clientId = UUID.randomUUID();
        Invoice invoice = approvedInvoice();
        invoice.setClientId(clientId);
        invoice.setEmail(null);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(
                Client.builder().clientId(clientId).email("synced-client@example.com").build()
        ));
        InvoiceResponseDto response = invoiceResponse(invoice);
        when(invoiceService.getInvoiceById(invoiceId)).thenReturn(response);
        when(invoiceDocumentService.generateInvoicePdf(response)).thenReturn(new byte[]{1});
        when(emailService.sendEmailWithAttachment(
                eq("synced-client@example.com"), anyString(), anyString(), any(byte[].class), anyString(), anyString()
        )).thenReturn("<message-id@gmail.com>");

        InvoiceDeliveryResponseDto result = invoiceDeliveryService.sendInvoice(invoiceId);

        assertThat(result.getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.SENT);
        assertThat(result.getRecipientEmail()).isEqualTo("synced-client@example.com");
        verify(emailService).sendEmailWithAttachment(
                eq("synced-client@example.com"), anyString(), anyString(), any(byte[].class), anyString(), anyString()
        );
    }

    // 3. Invoice not APPROVED.
    @Test
    void sendInvoice_invoiceNotApproved_throwsValidationException() {
        Invoice invoice = approvedInvoice();
        invoice.setStatus(InvoiceStatus.GENERATED);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceDeliveryService.sendInvoice(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Invoice must be approved before it can be sent to the client.");

        verifyNoInteractions(invoiceDeliveryRepository, emailService, invoiceDocumentService);
    }

    // 4. Missing client email.
    @Test
    void sendInvoice_missingClientEmail_throwsValidationException() {
        Invoice invoice = approvedInvoice();
        invoice.setEmail(null);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceDeliveryService.sendInvoice(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Client email is not configured for this invoice.");

        verifyNoInteractions(invoiceDeliveryRepository, emailService, invoiceDocumentService);
    }

    // 9. Sending an already-approved invoice must NOT require (or fetch)
    // the currently active Company Profile - it renders purely from the
    // Invoice's own frozen seller snapshot. This test never stubs any
    // Company Profile lookup at all (the dependency no longer exists on
    // this class), proving delivery is independent of it.
    @Test
    void sendInvoice_doesNotDependOnActiveCompanyProfile() {
        Invoice invoice = approvedInvoice();
        InvoiceResponseDto response = invoiceResponse(invoice);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceService.getInvoiceById(invoiceId)).thenReturn(response);
        when(invoiceDocumentService.generateInvoicePdf(response)).thenReturn(new byte[]{1, 2, 3});
        when(emailService.sendEmailWithAttachment(
                anyString(), anyString(), anyString(), any(byte[].class), anyString(), anyString()
        )).thenReturn("<message-id@gmail.com>");

        InvoiceDeliveryResponseDto result = invoiceDeliveryService.sendInvoice(invoiceId);

        assertThat(result.getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.SENT);
        verify(invoiceDocumentService).generateInvoicePdf(response);
    }

    // 6. PDF generation failure -> 9. Delivery status becomes FAILED after email failure (PDF variant).
    @Test
    void sendInvoice_pdfGenerationFails_marksDeliveryFailed() {
        Invoice invoice = approvedInvoice();
        InvoiceResponseDto response = invoiceResponse(invoice);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceService.getInvoiceById(invoiceId)).thenReturn(response);
        when(invoiceDocumentService.generateInvoicePdf(response))
                .thenThrow(new PdfGenerationException("Rendering failed.", new RuntimeException("boom")));

        InvoiceDeliveryResponseDto result = invoiceDeliveryService.sendInvoice(invoiceId);

        assertThat(result.getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.FAILED);
        assertThat(result.getFailureReason()).contains("PDF generation failed");
        assertThat(result.getFailedAt()).isNotNull();
        verifyNoInteractions(emailService);

        ArgumentCaptor<InvoiceDelivery> captor = ArgumentCaptor.forClass(InvoiceDelivery.class);
        verify(invoiceDeliveryRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(InvoiceDeliveryStatus.FAILED);
        verifyNoInteractions(receivableService);
    }

    // 7. Gmail/email service failure -> 9. Delivery status becomes FAILED after email failure.
    @Test
    void sendInvoice_emailServiceFails_marksDeliveryFailedNotSent() {
        Invoice invoice = approvedInvoice();
        InvoiceResponseDto response = invoiceResponse(invoice);

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceService.getInvoiceById(invoiceId)).thenReturn(response);
        when(invoiceDocumentService.generateInvoicePdf(response))
                .thenReturn(new byte[]{1, 2, 3});
        when(emailService.sendEmailWithAttachment(
                anyString(), anyString(), anyString(), any(byte[].class), anyString(), anyString()
        )).thenThrow(new EmailDeliveryException(
                "Failed to send invoice email through the configured mail provider.",
                new RuntimeException("SMTP auth failed")
        ));

        InvoiceDeliveryResponseDto result = invoiceDeliveryService.sendInvoice(invoiceId);

        assertThat(result.getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.FAILED);
        assertThat(result.getFailureReason())
                .isEqualTo("Failed to send invoice email through the configured mail provider.");
        assertThat(result.getFailedAt()).isNotNull();

        // Never silently marked SENT.
        assertThat(result.getDeliveryStatus()).isNotEqualTo(InvoiceDeliveryStatus.SENT);
        assertThat(result.getSentAt()).isNull();

        // A FAILED delivery never creates a receivable.
        verifyNoInteractions(receivableService);
    }

    // 10. Delivery history retrieval.
    @Test
    void getDeliveryHistory_returnsAttemptsNewestFirst() {
        Invoice invoice = approvedInvoice();

        InvoiceDelivery latest = InvoiceDelivery.builder()
                .invoiceDeliveryId(UUID.randomUUID())
                .invoiceId(invoiceId)
                .recipientEmail("client@example.com")
                .status(InvoiceDeliveryStatus.SENT)
                .build();

        InvoiceDelivery earlier = InvoiceDelivery.builder()
                .invoiceDeliveryId(UUID.randomUUID())
                .invoiceId(invoiceId)
                .recipientEmail("client@example.com")
                .status(InvoiceDeliveryStatus.FAILED)
                .failureReason("SMTP timeout")
                .build();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoiceDeliveryRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId))
                .thenReturn(List.of(latest, earlier));

        List<InvoiceDeliveryResponseDto> history = invoiceDeliveryService.getDeliveryHistory(invoiceId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.SENT);
        assertThat(history.get(1).getDeliveryStatus()).isEqualTo(InvoiceDeliveryStatus.FAILED);
        assertThat(history.get(1).getFailureReason()).isEqualTo("SMTP timeout");
        assertThat(history).allSatisfy(d -> assertThat(d.getInvoiceNumber()).isEqualTo("INV-20260908164549"));
    }

    @Test
    void getDeliveryHistory_invoiceNotFound_throwsResourceNotFoundException() {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceDeliveryService.getDeliveryHistory(invoiceId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class);
    }
}
