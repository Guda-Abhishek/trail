package com.AccountReceivableManagement.service_Imple.company_profile;

import com.AccountReceivableManagement.dto.company_profile.CompanyProfileRequestDto;
import com.AccountReceivableManagement.dto.company_profile.CompanyProfileResponseDto;
import com.AccountReceivableManagement.dto.company_profile.InvoiceContentDefaultsRequestDto;
import com.AccountReceivableManagement.dto.company_profile.InvoiceContentDefaultsResponseDto;
import com.AccountReceivableManagement.entity.company_profile.CompanyProfile;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.company_profile.CompanyProfileRepository;
import com.AccountReceivableManagement.service_interface.company_profile.CompanyProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyProfileServiceImpl implements CompanyProfileService {

    private final CompanyProfileRepository companyProfileRepository;

    @Override
    public CompanyProfileResponseDto create(CompanyProfileRequestDto request) {

        /*
         * The AR system supports exactly one Company Profile (its own
         * seller identity) - not multi-company. A second POST is rejected
         * outright rather than silently creating a second active row, since
         * CompanyProfileRepository has no uniqueness constraint of its own
         * to fall back on.
         */
        if (companyProfileRepository.count() > 0) {
            throw new GlobalExceptionHandler.DuplicateResourceException(
                    "A company profile has already been configured. Only one company profile is supported; use PUT to edit it."
            );
        }

        CompanyProfile companyProfile =
                CompanyProfile.builder()
                        .legalName(request.getLegalName().trim())
                        .addressLine1(request.getAddressLine1())
                        .addressLine2(request.getAddressLine2())
                        .city(request.getCity())
                        .state(request.getState())
                        .postalCode(request.getPostalCode())
                        .country(request.getCountry())
                        .gstin(request.getGstin())
                        .email(request.getEmail())
                        .phone(request.getPhone())
                        .logoReference(request.getLogoReference())
                        .defaultInvoiceNotes(blankToNull(request.getDefaultInvoiceNotes()))
                        .defaultTermsAndConditions(blankToNull(request.getDefaultTermsAndConditions()))
                        .defaultPaymentInstructions(blankToNull(request.getDefaultPaymentInstructions()))
                        .isActive(true)
                        .build();

        return mapToResponse(companyProfileRepository.save(companyProfile));
    }

    @Override
    public CompanyProfileResponseDto update(
            UUID companyProfileId,
            CompanyProfileRequestDto request
    ) {

        CompanyProfile companyProfile =
                companyProfileRepository.findById(companyProfileId)
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "Company profile could not be found."
                        ));

        companyProfile.setLegalName(request.getLegalName().trim());
        companyProfile.setAddressLine1(request.getAddressLine1());
        companyProfile.setAddressLine2(request.getAddressLine2());
        companyProfile.setCity(request.getCity());
        companyProfile.setState(request.getState());
        companyProfile.setPostalCode(request.getPostalCode());
        companyProfile.setCountry(request.getCountry());
        companyProfile.setGstin(request.getGstin());
        companyProfile.setEmail(request.getEmail());
        companyProfile.setPhone(request.getPhone());
        companyProfile.setLogoReference(request.getLogoReference());

        // A full profile PUT that omits the invoice-content fields must not
        // wipe the configured defaults; clearing goes through the
        // dedicated invoice-content-defaults endpoint.
        if (request.getDefaultInvoiceNotes() != null) {
            companyProfile.setDefaultInvoiceNotes(blankToNull(request.getDefaultInvoiceNotes()));
        }
        if (request.getDefaultTermsAndConditions() != null) {
            companyProfile.setDefaultTermsAndConditions(blankToNull(request.getDefaultTermsAndConditions()));
        }
        if (request.getDefaultPaymentInstructions() != null) {
            companyProfile.setDefaultPaymentInstructions(blankToNull(request.getDefaultPaymentInstructions()));
        }

        return mapToResponse(companyProfileRepository.save(companyProfile));
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceContentDefaultsResponseDto getInvoiceContentDefaults() {
        return mapToContentDefaults(findActiveEntity());
    }

    @Override
    public InvoiceContentDefaultsResponseDto updateInvoiceContentDefaults(
            InvoiceContentDefaultsRequestDto request
    ) {

        CompanyProfile companyProfile = findActiveEntity();

        companyProfile.setDefaultInvoiceNotes(blankToNull(request.getInvoiceNotes()));
        companyProfile.setDefaultTermsAndConditions(blankToNull(request.getTermsAndConditions()));
        companyProfile.setDefaultPaymentInstructions(blankToNull(request.getPaymentInstructions()));

        return mapToContentDefaults(companyProfileRepository.save(companyProfile));
    }

    private CompanyProfile findActiveEntity() {
        return companyProfileRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc()
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                        "No active company profile has been configured."
                ));
    }

    private InvoiceContentDefaultsResponseDto mapToContentDefaults(CompanyProfile companyProfile) {
        return InvoiceContentDefaultsResponseDto.builder()
                .companyProfileId(companyProfile.getCompanyProfileId())
                .invoiceNotes(companyProfile.getDefaultInvoiceNotes())
                .termsAndConditions(companyProfile.getDefaultTermsAndConditions())
                .paymentInstructions(companyProfile.getDefaultPaymentInstructions())
                .updatedAt(companyProfile.getUpdatedAt())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyProfileResponseDto getById(UUID companyProfileId) {

        CompanyProfile companyProfile =
                companyProfileRepository.findById(companyProfileId)
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "Company profile could not be found."
                        ));

        return mapToResponse(companyProfile);
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyProfileResponseDto getActive() {

        CompanyProfile companyProfile =
                companyProfileRepository.findFirstByIsActiveTrueOrderByCreatedAtAsc()
                        .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                                "No active company profile has been configured."
                        ));

        return mapToResponse(companyProfile);
    }

    private CompanyProfileResponseDto mapToResponse(CompanyProfile companyProfile) {

        return CompanyProfileResponseDto.builder()
                .companyProfileId(companyProfile.getCompanyProfileId())
                .legalName(companyProfile.getLegalName())
                .addressLine1(companyProfile.getAddressLine1())
                .addressLine2(companyProfile.getAddressLine2())
                .city(companyProfile.getCity())
                .state(companyProfile.getState())
                .postalCode(companyProfile.getPostalCode())
                .country(companyProfile.getCountry())
                .gstin(companyProfile.getGstin())
                .email(companyProfile.getEmail())
                .phone(companyProfile.getPhone())
                .logoReference(companyProfile.getLogoReference())
                .authorizedSignatureReference(companyProfile.getAuthorizedSignatureReference())
                .defaultInvoiceNotes(companyProfile.getDefaultInvoiceNotes())
                .defaultTermsAndConditions(companyProfile.getDefaultTermsAndConditions())
                .defaultPaymentInstructions(companyProfile.getDefaultPaymentInstructions())
                .isActive(companyProfile.getIsActive())
                .createdAt(companyProfile.getCreatedAt())
                .updatedAt(companyProfile.getUpdatedAt())
                .build();
    }
}
