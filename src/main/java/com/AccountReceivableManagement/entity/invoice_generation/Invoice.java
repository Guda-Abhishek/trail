package com.AccountReceivableManagement.entity.invoice_generation;

import com.AccountReceivableManagement.entity_enums.invoice_generation.InvoiceStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The frozen, final commercial document generated for exactly one
 * {@code BillingSnapshot} once its {@code TaxCalculation} is complete.
 * Holds a point-in-time copy of client, project, and financial figures
 * (never re-derived), so the invoice stays historically accurate even if
 * the source Client/Project/BillingConfiguration master data changes
 * afterwards. References its sources by plain UUID columns rather than
 * JPA relationships, matching the convention already used by
 * {@code TaxCalculation} itself.
 */
@Entity
@Table(
        name = "invoice",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_invoice_billing_schedule",
                        columnNames = {"billing_schedule_id"}
                ),
                @UniqueConstraint(
                        name = "uk_invoice_number",
                        columnNames = {"invoice_number"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_invoice_tax_calculation",
                        columnList = "tax_calculation_id"
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(
            name = "invoice_number",
            nullable = false,
            unique = true,
            length = 30
    )
    private String invoiceNumber;

    @Column(name = "billing_snapshot_id")
    private UUID billingSnapshotId;

    @Column(name = "billing_schedule_id", unique = true)
    private UUID billingScheduleId;

    /**
     * Human-readable {@code BillingSnapshot.snapshotNumber}, frozen onto the
     * invoice at generation time - the snapshot's own number is immutable
     * once acquired, so this is a safe, non-recalculated copy through the
     * existing billingSnapshotId relationship. Avoids a join on every GET
     * and every row of the invoice list.
     */
    @Column(name = "billing_snapshot_number", length = 30)
    private String billingSnapshotNumber;

    @Column(
            name = "tax_calculation_id",
            nullable = false
    )
    private UUID taxCalculationId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "client_name", length = 255)
    private String clientName;

        @Column(name = "country_code", length = 20)
        private String countryCode;

    /**
     * Not yet available anywhere in the current data model (Client has no
     * billing address). Persisted as {@code null} until a source field is
     * introduced — see the Invoice Generation implementation report.
     */
    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    /**
     * Not yet available anywhere in the current data model (Client has no
     * GSTIN/tax-ID field). Persisted as {@code null} until a source field is
     * introduced — see the Invoice Generation implementation report.
     */
    @Column(name = "gstin_or_tax_id", length = 50)
    private String gstinOrTaxId;

    /**
     * Not yet available anywhere in the current data model (Client has no
     * contact field). Persisted as {@code null} until a source field is
     * introduced — see the Invoice Generation implementation report.
     */
    @Column(name = "contact", length = 255)
    private String contact;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "project_name", length = 255)
    private String projectName;

    @Column(name = "project_start_date")
    private LocalDate projectStartDate;

    @Column(name = "project_end_date")
    private LocalDate projectEndDate;

    @Column(name = "billing_period_start", nullable = false)
    private LocalDate billingPeriodStart;

    @Column(name = "billing_period_end", nullable = false)
    private LocalDate billingPeriodEnd;

    @Column(name = "currency_code", length = 10)
    private String currencyCode;

    @Column(name = "payment_term_code", length = 100)
    private String paymentTermCode;

    /**
     * Friendly {@code PaymentTermsMaster.paymentTermName} (e.g. "Net 30"),
     * frozen alongside {@code paymentTermCode} (which historically holds
     * the stringified {@code paymentDays} figure, e.g. "30") so a Preview
     * screen can show a readable label without a live master-data lookup.
     */
    @Column(name = "payment_term_name", length = 100)
    private String paymentTermName;

    /**
     * Frozen copy of {@code Client.email}, populated only when the source
     * field itself is populated - never guessed.
     */
    @Column(name = "email", length = 255)
    private String email;

    /**
     * Frozen copy of {@code Client.phone}, populated only when the source
     * field itself is populated - never guessed.
     */
    @Column(name = "phone", length = 30)
    private String phone;

    /**
     * Point-in-time seller/issuing-company snapshot, copied from the single
     * active {@code CompanyProfile} at generation time - the seller-side
     * counterpart of the client fields above. Frozen the same way: never
     * re-derived after generation, so editing the Company Profile later
     * never changes an already-generated invoice. A completely separate
     * concern from the client snapshot - this data never comes from
     * Client/CDC.
     */
    @Column(name = "seller_legal_name", length = 255)
    private String sellerLegalName;

    @Column(name = "seller_address_line1", length = 255)
    private String sellerAddressLine1;

    @Column(name = "seller_address_line2", length = 255)
    private String sellerAddressLine2;

    @Column(name = "seller_city", length = 100)
    private String sellerCity;

    @Column(name = "seller_state", length = 100)
    private String sellerState;

    @Column(name = "seller_postal_code", length = 20)
    private String sellerPostalCode;

    @Column(name = "seller_country", length = 100)
    private String sellerCountry;

    @Column(name = "seller_gstin", length = 50)
    private String sellerGstin;

    @Column(name = "seller_email", length = 255)
    private String sellerEmail;

    @Column(name = "seller_phone", length = 30)
    private String sellerPhone;

    /** Signature asset id frozen at generation; null on invoices issued before this existed or without a signature. */
    @Column(name = "seller_signature_reference", length = 36)
    private String sellerSignatureReference;

    @Column(name = "seller_logo_reference", length = 500)
    private String sellerLogoReference;

    /**
     * Notes, Terms & Conditions and Payment Instructions frozen at
     * generation from the Company Profile defaults. Never re-derived, so
     * later changes to the defaults do not alter an existing invoice. Null
     * on invoices generated before these columns existed or when no
     * default was configured.
     */
    @Column(name = "invoice_notes", columnDefinition = "TEXT")
    private String invoiceNotes;

    @Column(name = "terms_and_conditions", columnDefinition = "TEXT")
    private String termsAndConditions;

    @Column(name = "payment_instructions", columnDefinition = "TEXT")
    private String paymentInstructions;

    /**
     * Tax context the invoice's tax was calculated under, frozen at
     * generation: the Tax Region code, and the source (supplier) and
     * destination (customer) tax jurisdiction codes whose comparison chose
     * SAME_JURISDICTION or DIFFERENT_JURISDICTION components. Null when the
     * source data did not record them - never derived or defaulted.
     */
    @Column(name = "tax_region_code", length = 100)
    private String taxRegionCode;

    @Column(name = "source_tax_jurisdiction_code", length = 50)
    private String sourceTaxJurisdictionCode;

    @Column(name = "destination_tax_jurisdiction_code", length = 50)
    private String destinationTaxJurisdictionCode;

    @Column(
            name = "subtotal",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal subtotal;

    @Column(
            name = "total_tax_amount",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal totalTaxAmount;

    @Column(
            name = "grand_total",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal grandTotal;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /**
     * {@code null} when the billing snapshot's payment term could not be
     * resolved to a {@code paymentDays} figure — never guessed.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    /*
     * columnDefinition is explicit here (rather than relying on `length`)
     * because Hibernate 6's MySQLDialect maps a plain @Enumerated(STRING)
     * column to a native MySQL ENUM(...) type - frozen to whichever Java
     * constants exist at table-creation time - not VARCHAR. That silently
     * broke GENERATED -> PENDING_APPROVAL once PENDING_APPROVAL was added
     * after the table already existed ("Data truncated for column
     * 'status'"), since `ddl-auto=update` never widens an existing native
     * ENUM's value list. Forcing VARCHAR keeps this status lifecycle
     * (which will keep growing) from hitting that class of bug again.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR(20)"
    )
    private InvoiceStatus status;

    @OneToMany(
            mappedBy = "invoice",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(
            mappedBy = "invoice",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<InvoiceTaxComponent> taxComponents = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {

        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
