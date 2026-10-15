package com.AccountReceivableManagement.service_Imple.currency;

import com.AccountReceivableManagement.dto.currency.ExchangeRateBatchRequestDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateItemDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateResponseDto;
import com.AccountReceivableManagement.dto.currency.ExchangeRateSingleRequestDto;
import com.AccountReceivableManagement.entity.currency.ExchangeRateRecord;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.currency.ExchangeRateRecordRepository;
import com.AccountReceivableManagement.service_interface.currency.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateServiceImpl implements ExchangeRateService {

    private final ExchangeRateRecordRepository repository;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ExchangeRateResponseDto> createBatch(ExchangeRateBatchRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Batch request cannot be null.");
        }

        String sourceCurrency = normaliseCurrency(request.getSourceCurrency(), "Source currency");
        LocalDate effectiveDate = request.getEffectiveDate();
        if (effectiveDate == null) {
            throw new IllegalArgumentException("Effective date is required.");
        }

        String rateSource = request.getRateSource();
        if (rateSource == null || rateSource.isBlank()) {
            throw new IllegalArgumentException("Rate source is required.");
        }

        List<ExchangeRateItemDto> items = request.getRates();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one exchange rate must be provided.");
        }

        // Validate items and check for duplicates within the batch
        Set<String> seenTargets = new HashSet<>();
        for (ExchangeRateItemDto item : items) {
            if (item == null) {
                throw new IllegalArgumentException("Rate entry cannot be null.");
            }
            String targetCurrency = normaliseCurrency(item.getTargetCurrency(), "Target currency");
            if (sourceCurrency.equals(targetCurrency)) {
                throw new IllegalArgumentException("Target currency (" + targetCurrency + ") must differ from source currency (" + sourceCurrency + ").");
            }
            if (!seenTargets.add(targetCurrency)) {
                throw new IllegalArgumentException("Duplicate target currency in batch: " + targetCurrency + ".");
            }
            BigDecimal rate = item.getEffectiveRate();
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("Exchange rate for " + targetCurrency + " must be a positive number.");
            }
        }

        // Check against existing database records to preserve historical integrity and reject duplicate dates
        for (ExchangeRateItemDto item : items) {
            String target = normaliseCurrency(item.getTargetCurrency(), "Target currency");
            if (repository.findBySourceCurrencyAndTargetCurrencyAndEffectiveDate(sourceCurrency, target, effectiveDate).isPresent()) {
                throw new GlobalExceptionHandler.DuplicateResourceException(
                        "An exchange rate for " + sourceCurrency + " -> " + target + " on " + effectiveDate +
                        " already exists. Historical rate records cannot be overwritten.");
            }
        }

        String approvedBy = resolveAuditor();
        LocalDateTime now = LocalDateTime.now();

        List<ExchangeRateRecord> recordsToPersist = new ArrayList<>();
        for (ExchangeRateItemDto item : items) {
            String target = normaliseCurrency(item.getTargetCurrency(), "Target currency");
            BigDecimal rate = item.getEffectiveRate();

            ExchangeRateRecord record = ExchangeRateRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .sourceCurrency(sourceCurrency)
                    .targetCurrency(target)
                    .rate(rate)
                    .effectiveDate(effectiveDate)
                    .rateSource(rateSource.trim())
                    .approvedBy(approvedBy)
                    .approvedAt(now)
                    .createdAt(now)
                    .build();
            recordsToPersist.add(record);
        }

        try {
            List<ExchangeRateRecord> saved = repository.saveAll(recordsToPersist);
            repository.flush();
            log.info("[ExchangeRateBatchSaved] sourceCurrency={} effectiveDate={} count={}",
                    sourceCurrency, effectiveDate, saved.size());
            return saved.stream().map(this::toDto).collect(Collectors.toList());
        } catch (DataIntegrityViolationException ex) {
            log.warn("[ExchangeRateDuplicateConflict] pair duplicate on date={}: {}", effectiveDate, ex.getMessage());
            throw new GlobalExceptionHandler.DuplicateResourceException(
                    "An exchange rate for this currency pair and effective date already exists. Historical rate records cannot be overwritten.");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExchangeRateResponseDto createSingle(ExchangeRateSingleRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null.");
        }
        ExchangeRateItemDto item = ExchangeRateItemDto.builder()
                .targetCurrency(request.getTargetCurrency())
                .exchangeRate(request.getEffectiveRate())
                .build();

        ExchangeRateBatchRequestDto batch = ExchangeRateBatchRequestDto.builder()
                .sourceCurrency(request.getSourceCurrency())
                .effectiveDate(request.getEffectiveDate())
                .rateSource(request.getRateSource())
                .rates(Collections.singletonList(item))
                .build();

        List<ExchangeRateResponseDto> results = createBatch(batch);
        return results.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExchangeRateResponseDto> getExchangeRates(
            String sourceCurrency, String targetCurrency, LocalDate effectiveDate, String status) {
        String src = clean(sourceCurrency);
        String tgt = clean(targetCurrency);

        List<ExchangeRateRecord> records;
        if (src != null || tgt != null || effectiveDate != null) {
            records = repository.findFiltered(src, tgt, effectiveDate);
        } else {
            records = repository.findAllByOrderByEffectiveDateDescCreatedAtDesc();
        }

        return records.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponseDto getById(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ID cannot be empty.");
        }
        ExchangeRateRecord record = repository.findById(id)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                        "Exchange rate not found with id: " + id));
        return toDto(record);
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponseDto lookupRate(String sourceCurrency, String targetCurrency, LocalDate asOfDate) {
        String src = clean(sourceCurrency);
        String tgt = clean(targetCurrency);
        if (src == null || tgt == null || asOfDate == null) {
            throw new IllegalArgumentException("sourceCurrency, targetCurrency and asOfDate are required.");
        }
        ExchangeRateRecord record = repository.findBySourceCurrencyAndTargetCurrencyAndEffectiveDate(src, tgt, asOfDate)
                .orElseThrow(() -> new GlobalExceptionHandler.ResourceNotFoundException(
                        "No exchange rate found for " + src + " -> " + tgt + " on " + asOfDate + "."));
        return toDto(record);
    }

    private ExchangeRateResponseDto toDto(ExchangeRateRecord record) {
        return ExchangeRateResponseDto.builder()
                .id(record.getId())
                .exchangeRateId(record.getId())
                .sourceCurrency(record.getSourceCurrency())
                .targetCurrency(record.getTargetCurrency())
                .exchangeRate(record.getRate())
                .rate(record.getRate())
                .effectiveDate(record.getEffectiveDate())
                .rateSource(record.getRateSource())
                .status("ACTIVE")
                .isActive(true)
                .approvedBy(record.getApprovedBy())
                .approvedAt(record.getApprovedAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getCreatedAt())
                .build();
    }

    private static String normaliseCurrency(String code, String fieldName) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        if (!trimmed.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException(fieldName + " must be a 3-letter upper-case ISO currency code (e.g. INR, USD).");
        }
        return trimmed;
    }

    private static String clean(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String resolveAuditor() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                if (auth.getPrincipal() instanceof Jwt jwt) {
                    String email = jwt.getClaimAsString("email");
                    if (email != null && !email.isBlank()) return email;
                    String name = jwt.getClaimAsString("name");
                    if (name != null && !name.isBlank()) return name;
                    String sub = jwt.getSubject();
                    if (sub != null && !sub.isBlank()) return sub;
                }
                String name = auth.getName();
                if (name != null && !name.isBlank() && !"anonymousUser".equals(name)) return name;
            }
        } catch (Exception ignored) {
        }
        return "finance.approver";
    }
}
