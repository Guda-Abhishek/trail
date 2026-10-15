package com.AccountReceivableManagement.controller.company_profile;

import com.AccountReceivableManagement.dto.centralizeddto.ApiResponse;
import com.AccountReceivableManagement.dto.company_profile.CompanySignatureResponseDto;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.security.FinanceExecutiveAuthorizer;
import com.AccountReceivableManagement.service_interface.company_profile.CompanySignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Authorized-signatory signature of the Company Profile. Upload, replace and
 * delete require a verified JWT with the FINANCE_EXECUTIVE role; reading the
 * signature (needed by invoice preview) requires any verified, unexpired token
 * (no role), so the frontend must fetch the image with the Authorization header.
 */
@RestController
@RequestMapping("/api/v1/company-profile")
@RequiredArgsConstructor
public class CompanySignatureController {

    private final CompanySignatureService signatureService;
    private final FinanceExecutiveAuthorizer authorizer;

    @PutMapping(value = "/{companyProfileId}/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CompanySignatureResponseDto>> upload(
            @PathVariable UUID companyProfileId,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        authorizer.requireFinanceExecutive(authorization);
        return ok("Authorized signature saved successfully.", signatureService.upload(companyProfileId, file));
    }

    @GetMapping("/{companyProfileId}/signature")
    public ResponseEntity<ApiResponse<CompanySignatureResponseDto>> get(
            @PathVariable UUID companyProfileId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        authorizer.requireAuthenticated(authorization);
        return ok("Authorized signature retrieved successfully.", signatureService.get(companyProfileId));
    }

    @DeleteMapping("/{companyProfileId}/signature")
    public ResponseEntity<ApiResponse<CompanySignatureResponseDto>> remove(
            @PathVariable UUID companyProfileId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        authorizer.requireFinanceExecutive(authorization);
        return ok("Authorized signature removed successfully.", signatureService.remove(companyProfileId));
    }

    /** Serves a stored signature image by its opaque id (verified token required); only signature assets can be addressed. */
    @GetMapping("/signature-assets/{signatureReference}")
    public ResponseEntity<byte[]> image(
            @PathVariable String signatureReference,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        authorizer.requireAuthenticated(authorization);
        CompanySignatureService.SignatureImage image = signatureService.findImage(signatureReference)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                        "Signature image could not be found."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(image.content());
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().success(true).message(message).data(data).build());
    }
}
