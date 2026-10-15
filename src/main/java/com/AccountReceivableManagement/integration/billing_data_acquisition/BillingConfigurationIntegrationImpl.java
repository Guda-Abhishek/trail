package com.AccountReceivableManagement.integration.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingType;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.ApprovalStatus;
import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingConfigurationStatus;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BillingConfigurationIntegrationImpl implements BillingConfigurationIntegration {

    private final BillingConfigurationService billingConfigurationService;

    @Override
    public BillingConfigurationResponseDto getApprovedBillingConfiguration(Long projectId) {
        com.AccountReceivableManagement.dto.projectbilling_config.BillingConfigurationResponseDto epic1Configuration =
                billingConfigurationService.getApprovedByProjectId(projectId);
        return mapToDto(epic1Configuration);
    }

    @Override
    public BillingConfigurationResponseDto getApprovedBillingConfigurationById(UUID billingConfigurationId) {
        com.AccountReceivableManagement.dto.projectbilling_config.BillingConfigurationResponseDto epic1Configuration =
                billingConfigurationService.getBillingConfiguration(billingConfigurationId);
        return mapToDto(epic1Configuration);
    }

    private BillingConfigurationResponseDto mapToDto(
            com.AccountReceivableManagement.dto.projectbilling_config.BillingConfigurationResponseDto epic1Configuration) {

        if (epic1Configuration == null) {
            return null;
        }

        return BillingConfigurationResponseDto.builder()
                .billingConfigurationId(
                        epic1Configuration.getBillingConfigurationId())

                .projectId(
                        epic1Configuration.getProjectId())

                .billingType(
                        toBillingTypeEnum(
                                epic1Configuration.getBillingTypeName()))

                .billingTypeId(
                        epic1Configuration.getBillingTypeId())

                .billingTypeName(
                        epic1Configuration.getBillingTypeName())

                .currencyId(
                        epic1Configuration.getCurrencyId())

                .currencyCode(
                        epic1Configuration.getCurrency() != null
                                ? epic1Configuration.getCurrency()
                                : epic1Configuration.getCurrencyCode())

                .paymentTermId(
                        epic1Configuration.getPaymentTermId())

                .paymentTermCode(
                        epic1Configuration.getPaymentTermCode() != null
                                ? epic1Configuration.getPaymentTermCode()
                                : epic1Configuration.getPaymentTermName())

                .paymentTermName(
                        epic1Configuration.getPaymentTermName())

                .billingFrequencyId(
                        epic1Configuration.getBillingFrequencyId())

                .billingFrequencyName(
                        epic1Configuration.getBillingFrequencyName())

                .taxRegionId(
                        epic1Configuration.getTaxRegionId())

                .taxRegionCode(
                        epic1Configuration.getTaxRegionCode())

                .hourlyRate(
                        epic1Configuration.getHourlyRate())

                .pricingModel(
                        epic1Configuration.getPricingModel())

                .expenseEligible(
                        Boolean.TRUE.equals(
                                epic1Configuration.getExpenseBillingEligible()))

                // New two-status model
                .approved(
                        epic1Configuration.getApprovalStatus()
                                == ApprovalStatus.APPROVED)

                .build();
    }

    private static BillingType toBillingTypeEnum(String billingTypeName) {
        if (billingTypeName == null || billingTypeName.isBlank()) {
            return null;
        }

        String normalized = billingTypeName.trim()
                .replace("&", "AND")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase();

        if (normalized.contains("TIMESHEET") || normalized.contains("TIME_MATERIAL") || normalized.contains("TIME_AND_MATERIAL")) {
            return BillingType.TIME_AND_MATERIAL;
        }
        if (normalized.contains("FIXED")) {
            return BillingType.FIXED_PRICE;
        }
        if (normalized.contains("MILESTONE")) {
            return BillingType.MILESTONE;
        }
        if (normalized.contains("RECURRING") || normalized.contains("SUBSCRIPTION") || normalized.contains("RETAINER")) {
            return BillingType.RETAINER;
        }

        try {
            return BillingType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return BillingType.TIME_AND_MATERIAL;
        }
    }
}
