package com.AccountReceivableManagement.service_interface.projectbilling_config;

import com.AccountReceivableManagement.dto.projectbilling_config.BillingMilestonePlanRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingMilestonePlanResponseDto;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingConfiguration;

import java.util.Optional;
import java.util.UUID;

public interface BillingMilestonePlanService {

    BillingMilestonePlanResponseDto create(UUID billingConfigurationId, BillingMilestonePlanRequestDto request);

    BillingMilestonePlanResponseDto update(UUID milestonePlanId, BillingMilestonePlanRequestDto request);

    BillingMilestonePlanResponseDto get(UUID milestonePlanId);

    BillingMilestonePlanResponseDto getByBillingConfiguration(UUID billingConfigurationId);

    Optional<BillingMilestonePlanResponseDto> findActiveByBillingConfiguration(BillingConfiguration configuration);

    void delete(UUID milestonePlanId);

}
