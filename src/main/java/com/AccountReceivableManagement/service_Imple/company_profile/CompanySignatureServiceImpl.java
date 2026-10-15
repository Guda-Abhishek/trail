package com.AccountReceivableManagement.service_Imple.company_profile;

import com.AccountReceivableManagement.dto.company_profile.CompanySignatureResponseDto;
import com.AccountReceivableManagement.entity.company_profile.CompanyProfile;
import com.AccountReceivableManagement.entity.company_profile.CompanySignatureAsset;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.company_profile.CompanyProfileRepository;
import com.AccountReceivableManagement.repo.company_profile.CompanySignatureAssetRepository;
import com.AccountReceivableManagement.service_interface.company_profile.CompanySignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanySignatureServiceImpl implements CompanySignatureService {

    public static final String IMAGE_URL_PREFIX = "/api/v1/company-profile/signature-assets/";

    private final CompanyProfileRepository companyProfileRepository;
    private final CompanySignatureAssetRepository assetRepository;
    private final SignatureImageValidator validator;

    @Override
    @Transactional
    public CompanySignatureResponseDto upload(UUID companyProfileId, MultipartFile file) {
        CompanyProfile profile = findProfile(companyProfileId);
        SignatureImageValidator.ValidatedImage image = validator.validate(file);

        CompanySignatureAsset asset = assetRepository.save(CompanySignatureAsset.builder()
                .id(UUID.randomUUID().toString())
                .companyProfileId(companyProfileId.toString())
                .contentType(image.contentType())
                .sizeBytes(image.content().length)
                .sha256(sha256(image.content()))
                .content(image.content())
                .build());

        profile.setAuthorizedSignatureReference(asset.getId());
        companyProfileRepository.save(profile);
        log.info("[CompanySignature] profile={} signature set to asset={} ({} bytes, {})",
                companyProfileId, asset.getId(), asset.getSizeBytes(), asset.getContentType());
        return toResponse(companyProfileId, asset);
    }

    @Override
    @Transactional(readOnly = true)
    public CompanySignatureResponseDto get(UUID companyProfileId) {
        CompanyProfile profile = findProfile(companyProfileId);
        String reference = profile.getAuthorizedSignatureReference();
        if (reference == null) {
            return none(companyProfileId);
        }
        return assetRepository.findById(reference)
                .map(asset -> toResponse(companyProfileId, asset))
                .orElseGet(() -> {
                    log.warn("[CompanySignature] profile={} references missing asset={}", companyProfileId, reference);
                    return none(companyProfileId);
                });
    }

    @Override
    @Transactional
    public CompanySignatureResponseDto remove(UUID companyProfileId) {
        CompanyProfile profile = findProfile(companyProfileId);
        if (profile.getAuthorizedSignatureReference() != null) {
            profile.setAuthorizedSignatureReference(null);
            companyProfileRepository.save(profile);
            log.info("[CompanySignature] profile={} signature removed", companyProfileId);
        }
        return none(companyProfileId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SignatureImage> findImage(String signatureReference) {
        if (signatureReference == null || signatureReference.isBlank()) {
            return Optional.empty();
        }
        return assetRepository.findById(signatureReference)
                .map(a -> new SignatureImage(a.getContentType(), a.getContent()));
    }

    private CompanyProfile findProfile(UUID id) {
        return companyProfileRepository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                        "Company profile could not be found."));
    }

    private static CompanySignatureResponseDto none(UUID profileId) {
        return CompanySignatureResponseDto.builder().companyProfileId(profileId).hasSignature(false).build();
    }

    private static CompanySignatureResponseDto toResponse(UUID profileId, CompanySignatureAsset asset) {
        return CompanySignatureResponseDto.builder()
                .companyProfileId(profileId)
                .hasSignature(true)
                .signatureReference(asset.getId())
                .imageUrl(IMAGE_URL_PREFIX + asset.getId())
                .contentType(asset.getContentType())
                .sizeBytes(asset.getSizeBytes())
                .uploadedAt(asset.getCreatedAt())
                .build();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
