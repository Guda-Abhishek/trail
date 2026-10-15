package com.AccountReceivableManagement.dto.company_profile;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Signature availability and a usable reference; never exposes storage internals or image bytes. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySignatureResponseDto {

    private UUID companyProfileId;

    private boolean hasSignature;

    /** Opaque id of the current signature asset; null when none is configured. */
    private String signatureReference;

    /** Relative API path that serves the image; null when none is configured. */
    private String imageUrl;

    private String contentType;

    private Long sizeBytes;

    private LocalDateTime uploadedAt;
}
