package com.AccountReceivableManagement.service_interface.company_profile;

import com.AccountReceivableManagement.dto.company_profile.CompanySignatureResponseDto;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

/** Authorized-signatory signature of the Company Profile. Authorization is enforced by the caller (controller). */
public interface CompanySignatureService {

    /** Validates and stores the image, then makes it the profile's current signature (replacing any previous one). */
    CompanySignatureResponseDto upload(UUID companyProfileId, MultipartFile file);

    CompanySignatureResponseDto get(UUID companyProfileId);

    /** Clears the profile's current signature. Stored images stay, because issued invoices reference them. */
    CompanySignatureResponseDto remove(UUID companyProfileId);

    /** Image of a stored signature asset, or empty when it no longer exists. */
    Optional<SignatureImage> findImage(String signatureReference);

    record SignatureImage(String contentType, byte[] content) {
    }
}
