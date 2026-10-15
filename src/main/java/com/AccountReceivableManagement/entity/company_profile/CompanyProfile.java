package com.AccountReceivableManagement.entity.company_profile;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The issuing/seller company's own commercial details (legal name,
 * registered address, GSTIN, contact) - the counterpart of {@code Client}
 * on the other side of an invoice. Nothing else in this codebase
 * represents this data; it did not exist anywhere before this entity.
 */
@Entity
@Table(name = "company_profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "company_profile_id")
    private UUID companyProfileId;

    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "gstin", length = 50)
    private String gstin;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    /** Opaque id of the current authorized-signatory signature asset (company_signature_asset); null when none. */
    @Column(name = "authorized_signature_reference", length = 36)
    private String authorizedSignatureReference;

    @Column(name = "logo_reference", length = 500)
    private String logoReference;

    /**
     * Configurable defaults copied onto each newly generated invoice. Null
     * until a business user configures them - never populated with
     * invented wording.
     */
    @Column(name = "default_invoice_notes", columnDefinition = "TEXT")
    private String defaultInvoiceNotes;

    @Column(name = "default_terms_and_conditions", columnDefinition = "TEXT")
    private String defaultTermsAndConditions;

    @Column(name = "default_payment_instructions", columnDefinition = "TEXT")
    private String defaultPaymentInstructions;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();

        if (isActive == null) {
            isActive = true;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
