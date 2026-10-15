package com.AccountReceivableManagement.controller.projectbilling_config;

import com.AccountReceivableManagement.dto.centralizeddto.ApiResponse;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateDiscountRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.BillingTMRateDiscountResponseDto;
import com.AccountReceivableManagement.service_interface.projectbilling_config.BillingTMRateDiscountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/billing-tm-rate-card")
@RequiredArgsConstructor
public class BillingTMRateDiscountController {

    private final BillingTMRateDiscountService discountService;

    @PostMapping("/tm-rate-cards/{rateCardId}/discounts")
    public ResponseEntity<ApiResponse<BillingTMRateDiscountResponseDto>> addDiscount(
            @PathVariable UUID rateCardId,
            @Valid @RequestBody BillingTMRateDiscountRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<BillingTMRateDiscountResponseDto>builder()
                        .success(true)
                        .message("Discount period created successfully.")
                        .data(discountService.addDiscount(rateCardId, request))
                        .build());
    }

    @GetMapping("/tm-rate-cards/{rateCardId}/discounts")
    public ResponseEntity<ApiResponse<List<BillingTMRateDiscountResponseDto>>> getDiscounts(
            @PathVariable UUID rateCardId) {
        return ResponseEntity.ok(ApiResponse.<List<BillingTMRateDiscountResponseDto>>builder()
                .success(true)
                .message("Discount periods fetched successfully.")
                .data(discountService.getDiscounts(rateCardId))
                .build());
    }

    @GetMapping("/tm-rate-discounts/{discountId}")
    public ResponseEntity<ApiResponse<BillingTMRateDiscountResponseDto>> getDiscount(
            @PathVariable UUID discountId) {
        return ResponseEntity.ok(ApiResponse.<BillingTMRateDiscountResponseDto>builder()
                .success(true)
                .message("Discount period fetched successfully.")
                .data(discountService.getDiscount(discountId))
                .build());
    }

    @PutMapping("/tm-rate-discounts/{discountId}")
    public ResponseEntity<ApiResponse<BillingTMRateDiscountResponseDto>> updateDiscount(
            @PathVariable UUID discountId,
            @Valid @RequestBody BillingTMRateDiscountRequestDto request) {
        return ResponseEntity.ok(ApiResponse.<BillingTMRateDiscountResponseDto>builder()
                .success(true)
                .message("Discount period updated successfully.")
                .data(discountService.updateDiscount(discountId, request))
                .build());
    }

    @DeleteMapping("/tm-rate-discounts/{discountId}")
    public ResponseEntity<ApiResponse<Void>> deleteDiscount(@PathVariable UUID discountId) {
        discountService.deleteDiscount(discountId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Discount period deleted successfully.")
                .build());
    }
}
