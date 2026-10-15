package com.AccountReceivableManagement.service_interface.projectbilling_config;

import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateDiscountRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateDiscountResponseDto;

import java.util.List;
import java.util.UUID;

public interface BillingTMRateDiscountService {

    BillingTMRateDiscountResponseDto addDiscount(UUID rateCardId, BillingTMRateDiscountRequestDto request);

    BillingTMRateDiscountResponseDto updateDiscount(UUID discountId, BillingTMRateDiscountRequestDto request);

    List<BillingTMRateDiscountResponseDto> getDiscounts(UUID rateCardId);

    BillingTMRateDiscountResponseDto getDiscount(UUID discountId);

    void deleteDiscount(UUID discountId);
}
