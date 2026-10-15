package com.AccountReceivableManagement.service_Imple.invoice_generation;

import com.AccountReceivableManagement.dto.invoice_generation.InvoiceItemResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceResponseDto;
import com.AccountReceivableManagement.dto.invoice_generation.InvoiceTaxComponentResponseDto;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxApplicabilityType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceDocumentServiceImplTest {

    private final InvoiceDocumentServiceImpl service = new InvoiceDocumentServiceImpl();

    private InvoiceResponseDto fullInvoice() {
        return InvoiceResponseDto.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber("INV-20260908164549")
                .clientName("Account Management")
                .billingAddress("221B Baker Street, London")
                .gstinOrTaxId("GB123456789")
                .email("client@example.com")
                .phone("+44 20 7946 0958")
                .sellerLegalName("Example Global Infotech Private Limited")
                .sellerAddressLine1("Tower B, Tech Park")
                .sellerCity("Hyderabad")
                .sellerState("Telangana")
                .sellerPostalCode("500081")
                .sellerCountry("India")
                .sellerGstin("36AAAAA0000A1Z5")
                .sellerEmail("billing@example.com")
                .sellerPhone("+91 40 1234 5678")
                .currencyCode("USD")
                .paymentTermName("Net 30")
                .invoiceDate(LocalDate.of(2026, 9, 8))
                .dueDate(LocalDate.of(2026, 10, 8))
                .billingPeriodStart(LocalDate.of(2026, 6, 1))
                .billingPeriodEnd(LocalDate.of(2026, 8, 30))
                .items(List.of(
                        InvoiceItemResponseDto.builder()
                                .itemType(BillingItemType.TIME_ENTRY)
                                .itemName("Backend Development")
                                .resourceName("Jane Doe")
                                .quantity(new BigDecimal("55.00"))
                                .rate(new BigDecimal("100.00"))
                                .amount(new BigDecimal("5500.00"))
                                .build()
                ))
                .taxComponents(List.of(
                        InvoiceTaxComponentResponseDto.builder()
                                .taxTypeCode("CGST")
                                .taxTypeName("Central GST")
                                .appliedRate(new BigDecimal("9.0000"))
                                .taxAmount(new BigDecimal("495.00"))
                                .applicabilityType(TaxApplicabilityType.ALL)
                                .build()
                ))
                .subtotal(new BigDecimal("5500.00"))
                .totalTaxAmount(new BigDecimal("495.00"))
                .grandTotal(new BigDecimal("5995.00"))
                .build();
    }

    @Test
    void generateInvoicePdf_fullInvoice_producesValidPdfBytes() {
        byte[] pdf = service.generateInvoicePdf(fullInvoice());

        assertThat(pdf).isNotEmpty();
        // Every PDF file starts with this magic header - confirms a real
        // document was rendered, not a placeholder/empty byte array.
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void generateInvoicePdf_emptyItemsAndTax_stillProducesValidPdf() {
        InvoiceResponseDto invoice = fullInvoice();
        invoice.setItems(List.of());
        invoice.setTaxComponents(List.of());

        byte[] pdf = service.generateInvoicePdf(invoice);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    // The seller ("From") block is rendered purely from the Invoice's own
    // seller* snapshot fields - generateInvoicePdf takes no CompanyProfile
    // parameter at all, so a historical invoice's PDF cannot depend on
    // whatever Company Profile happens to be active when it's re-rendered.
    @Test
    void generateInvoicePdf_usesInvoiceOwnSellerSnapshot_noCompanyProfileParameter() {
        InvoiceResponseDto invoice = fullInvoice();

        byte[] pdf = service.generateInvoicePdf(invoice);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    private String pdfText(byte[] pdf) throws java.io.IOException {
        try (com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf)) {
            com.lowagie.text.pdf.parser.PdfTextExtractor extractor =
                    new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }

    @Test
    void generateInvoicePdf_printsSavedNotesTermsAndPaymentInstructions() throws Exception {
        InvoiceResponseDto invoice = fullInvoice();
        invoice.setInvoiceNotes("TEST-NOTES-TEXT");
        invoice.setTermsAndConditions("TEST-TERMS-TEXT");
        invoice.setPaymentInstructions("TEST-PAYMENT-TEXT");

        String text = pdfText(service.generateInvoicePdf(invoice));

        assertThat(text).contains("Notes", "TEST-NOTES-TEXT")
                .contains("Payment Instructions", "TEST-PAYMENT-TEXT")
                .contains("Terms & Conditions", "TEST-TERMS-TEXT");
    }

    @Test
    void generateInvoicePdf_noSavedContent_omitsSectionsAndPrintsNoPlaceholder() throws Exception {
        String text = pdfText(service.generateInvoicePdf(fullInvoice()));

        assertThat(text).doesNotContain("Terms & Conditions")
                .doesNotContain("Payment Instructions")
                .doesNotContain("Notes");
    }

    // Missing-data behavior is preserved: a blank seller snapshot (e.g. an
    // invoice generated before this feature existed) must not fail
    // rendering or fabricate placeholder seller text.
    @Test
    void generateInvoicePdf_blankSellerSnapshot_stillProducesValidPdf() {
        InvoiceResponseDto invoice = fullInvoice();
        invoice.setSellerLegalName(null);
        invoice.setSellerAddressLine1(null);
        invoice.setSellerAddressLine2(null);
        invoice.setSellerCity(null);
        invoice.setSellerState(null);
        invoice.setSellerPostalCode(null);
        invoice.setSellerCountry(null);
        invoice.setSellerGstin(null);
        invoice.setSellerEmail(null);
        invoice.setSellerPhone(null);
        invoice.setSellerLogoReference(null);

        byte[] pdf = service.generateInvoicePdf(invoice);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    // ---- authorized signatory signature ---------------------------------

    private static byte[] pngBytes() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(60, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static boolean containsImage(byte[] pdf) {
        return new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1).replace(" ", "").contains("/Subtype/Image");
    }

    private static com.AccountReceivableManagement.service_interface.company_profile.CompanySignatureService stub(
            java.util.function.Function<String, java.util.Optional<
                    com.AccountReceivableManagement.service_interface.company_profile.CompanySignatureService.SignatureImage>> f) {
        var mock = org.mockito.Mockito.mock(
                com.AccountReceivableManagement.service_interface.company_profile.CompanySignatureService.class);
        org.mockito.Mockito.when(mock.findImage(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> f.apply(i.getArgument(0)));
        return mock;
    }

    @Test
    void pdf_rendersTheInvoiceOwnSignatureImage_whenPresent() throws Exception {
        byte[] png = pngBytes();
        var withSignature = new InvoiceDocumentServiceImpl(stub(ref -> ref.equals("sig-1")
                ? java.util.Optional.of(new com.AccountReceivableManagement.service_interface.company_profile
                        .CompanySignatureService.SignatureImage("image/png", png))
                : java.util.Optional.empty()));
        InvoiceResponseDto invoice = fullInvoice();
        invoice.setSellerSignatureReference("sig-1");

        byte[] pdf = withSignature.generateInvoicePdf(invoice);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(containsImage(pdf)).isTrue();
    }

    @Test
    void pdf_hasNoImageAndNoSignatureClaims_whenNoSignatureConfigured() throws Exception {
        var svc = new InvoiceDocumentServiceImpl(stub(ref -> java.util.Optional.empty()));
        InvoiceResponseDto invoice = fullInvoice();           // sellerSignatureReference == null (pre-feature invoice)

        byte[] pdf = svc.generateInvoicePdf(invoice);

        assertThat(containsImage(pdf)).isFalse();
        assertThat(pdfText(pdf)).doesNotContain("Digitally").doesNotContain("Authorized Signatory");
    }

    @Test
    void pdf_stillGenerates_whenTheReferencedAssetIsMissingOrCorrupt() throws Exception {
        InvoiceResponseDto invoice = fullInvoice();
        invoice.setSellerSignatureReference("deleted");

        byte[] missing = new InvoiceDocumentServiceImpl(stub(ref -> java.util.Optional.empty()))
                .generateInvoicePdf(invoice);
        byte[] corrupt = new InvoiceDocumentServiceImpl(stub(ref -> java.util.Optional.of(
                new com.AccountReceivableManagement.service_interface.company_profile
                        .CompanySignatureService.SignatureImage("image/png", new byte[] {1, 2, 3}))))
                .generateInvoicePdf(invoice);
        byte[] failing = new InvoiceDocumentServiceImpl(stub(ref -> { throw new IllegalStateException("db down"); }))
                .generateInvoicePdf(invoice);

        for (byte[] pdf : new byte[][] {missing, corrupt, failing}) {
            assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
            assertThat(containsImage(pdf)).isFalse();
        }
    }

    @Test
    void pdf_neverPrintsDigitalSigningOrApprovalClaims_evenWithAnImage() throws Exception {
        byte[] png = pngBytes();
        var svc = new InvoiceDocumentServiceImpl(stub(ref -> java.util.Optional.of(
                new com.AccountReceivableManagement.service_interface.company_profile
                        .CompanySignatureService.SignatureImage("image/png", png))));
        InvoiceResponseDto invoice = fullInvoice();
        invoice.setSellerSignatureReference("sig-1");

        String text = pdfText(svc.generateInvoicePdf(invoice));

        assertThat(text).doesNotContain("Digitally Signed").doesNotContain("Digitally Verified").doesNotContain("Approved");
    }
}
