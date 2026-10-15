package com.AccountReceivableManagement.entity.company_profile;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One uploaded authorized-signatory signature image. Rows are immutable:
 * replacing the signature inserts a new row and repoints the Company Profile,
 * so invoices that froze an earlier reference keep rendering their own image.
 * The id is a random UUID string - never derived from the client's filename.
 */
@Entity
@Table(name = "company_signature_asset")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySignatureAsset {

    @Id
    @Column(name = "signature_asset_id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "company_profile_id", length = 36, nullable = false, updatable = false)
    private String companyProfileId;

    @Column(name = "content_type", length = 50, nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", length = 64, nullable = false, updatable = false)
    private String sha256;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "LONGBLOB")
    private byte[] content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
