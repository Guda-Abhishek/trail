package com.AccountReceivableManagement.dto.invoice_generation;

import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceResponseDto {

    private UUID invoiceId;

    private String invoiceNumber;

    private InvoiceStatus status;

    private UUID billingSnapshotId;

    private UUID billingScheduleId;

    private String billingSnapshotNumber;

    private UUID taxCalculationId;

    private UUID clientId;

    /**
     * {@code true} once an Invoice has been generated and persisted;
     * {@code false} for a pre-generation preview. Lets a consumer tell
     * "not yet generated" (invoice number, dates and status are null) apart
     * from a mapping defect.
     */
    private Boolean generated;

    /**
     * Project code from the billing configuration. Populated on the
     * pre-generation preview only; the persisted Invoice does not store it.
     */
    private String projectCode;

    private String clientName;

    private String countryCode;

    /**
     * Client country as stored on the Client (the only client location data
     * available - no city/state/address). Populated on the pre-generation
     * preview only; display data, never used for tax determination. Not the
     * seller location.
     */
    private String clientCountryName;

    private String clientCountryCode;

    private String billingAddress;

    private String gstinOrTaxId;

    private String contact;

    private Long projectId;

    private String projectName;

    private LocalDate projectStartDate;

    private LocalDate projectEndDate;

    private LocalDate billingPeriodStart;

    private LocalDate billingPeriodEnd;

    private String currencyCode;

    private String paymentTermCode;

    private String paymentTermName;

    private String email;

    private String phone;

    private String sellerLegalName;

    private String sellerAddressLine1;

    private String sellerAddressLine2;

    private String sellerCity;

    private String sellerState;

    private String sellerPostalCode;

    private String sellerCountry;

    private String sellerGstin;

    private String sellerEmail;

    private String sellerPhone;

    private String sellerLogoReference;

    /** Frozen signature asset id (null when none was configured at generation). */
    private String sellerSignatureReference;

    /**
     * Saved on the invoice at generation; on a preview (not yet generated)
     * these show the currently configured defaults that generation will copy.
     */
    private String invoiceNotes;

    private String termsAndConditions;

    private String paymentInstructions;

    /**
     * Tax context frozen on the invoice at generation - the Tax Region code,
     * and the supplier (source) and customer (destination) tax jurisdiction
     * codes used to select the tax components. Null when not recorded.
     */
    private String taxRegionCode;

    private String sourceTaxJurisdictionCode;

    private String destinationTaxJurisdictionCode;

    private LocalDate invoiceDate;

    private LocalDate dueDate;

    private List<InvoiceItemResponseDto> items;

    private List<InvoiceTaxComponentResponseDto> taxComponents;

    private BigDecimal subtotal;

    private BigDecimal totalTaxAmount;

    private BigDecimal grandTotal;

    private LocalDateTime generatedAt;

    /**
     * {@code true} only when {@code status == REJECTED} and no correction
     * refresh has occurred since the latest rejection - i.e. the invoice is
     * not yet safe to resubmit. Always {@code false} for every other
     * status. Derived from {@code InvoiceApprovalHistory}, never stored.
     */
    private boolean correctionRequired;

    /**
     * Timestamp of the most recent {@code CORRECTED} approval-history entry
     * for this invoice, or {@code null} if it has never been refreshed
     * after a rejection. Derived from {@code InvoiceApprovalHistory}, never
     * stored on the invoice itself.
     */
    private LocalDateTime lastCorrectedAt;
}
