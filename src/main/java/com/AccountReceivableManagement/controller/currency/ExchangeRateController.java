package com.AccountReceivableManagement.controller.currency;

import com.AccountReceivableManagement.dto.centralizeddto.ApiResponse;
import com.AccountReceivableManagement.dto.currency.ExchangeRateBatchRequestDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateResponseDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateSingleRequestDto;
import com.AccountReceivableManagement.service_interface.currency.ExchangeRateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExchangeRateResponseDto>>> getExchangeRates(
            @RequestParam(required = false) String sourceCurrency,
            @RequestParam(required = false) String targetCurrency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
            @RequestParam(required = false) String status) {

        List<ExchangeRateResponseDto> rates = exchangeRateService.getExchangeRates(
                sourceCurrency, targetCurrency, effectiveDate, status);

        return ok("Exchange rates retrieved successfully.", rates);
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<List<ExchangeRateResponseDto>>> createBatch(
            @Valid @RequestBody ExchangeRateBatchRequestDto request) {

        List<ExchangeRateResponseDto> response = exchangeRateService.createBatch(request);

        return created("Daily exchange rates saved successfully.", response);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExchangeRateResponseDto>> createSingle(
            @Valid @RequestBody ExchangeRateSingleRequestDto request) {

        ExchangeRateResponseDto response = exchangeRateService.createSingle(request);

        return created("Exchange rate created successfully.", response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExchangeRateResponseDto>> getById(@PathVariable String id) {
        ExchangeRateResponseDto response = exchangeRateService.getById(id);

        return ok("Exchange rate retrieved successfully.", response);
    }

    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<ExchangeRateResponseDto>> lookup(
            @RequestParam String sourceCurrency,
            @RequestParam String targetCurrency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate lookupDate = asOfDate != null ? asOfDate : (effectiveDate != null ? effectiveDate : date);
        if (lookupDate == null) {
            lookupDate = LocalDate.now();
        }

        ExchangeRateResponseDto response = exchangeRateService.lookupRate(sourceCurrency, targetCurrency, lookupDate);

        return ok("Exchange rate lookup successful.", response);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> created(String message, T data) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<T>builder()
                        .success(true)
                        .message(message)
                        .data(data)
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}