package com.AccountReceivableManagement.service_interface.currency;

import com.AccountReceivableManagement.dto.currency.ExchangeRateBatchRequestDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateResponseDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateSingleRequestDto;

import java.time.LocalDate;
import java.util.List;

public interface ExchangeRateService {

    List<ExchangeRateResponseDto> createBatch(ExchangeRateBatchRequestDto request);

    ExchangeRateResponseDto createSingle(ExchangeRateSingleRequestDto request);

    List<ExchangeRateResponseDto> getExchangeRates(
            String sourceCurrency, String targetCurrency, LocalDate effectiveDate, String status);

    ExchangeRateResponseDto getById(String id);

    ExchangeRateResponseDto lookupRate(String sourceCurrency, String targetCurrency, LocalDate asOfDate);
}
