package com.AccountReceivableManagement.entity.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One Finance-maintained, approved exchange rate: {@code rate} units of
 * {@code targetCurrency} per 1 unit of {@code sourceCurrency}, effective on
 * {@code effectiveDate}. There is at most one rate per (source, target, date) -
 * the unique constraint makes an ambiguous rate impossible. No rows are
 * shipped: Finance supplies and approves every rate, and nothing in AR reads
 * this table until an {@code ExchangeRateProvider} backed by it is approved.
 */
@Entity
@Table(name = "currency_exchange_rate",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_currency_exchange_rate_pair_date",
                columnNames = {"source_currency", "target_currency", "effective_date"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateRecord {

    @Id
    @Column(name = "exchange_rate_id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "source_currency", length = 10, nullable = false)
    private String sourceCurrency;

    @Column(name = "target_currency", length = 10, nullable = false)
    private String targetCurrency;

    /** Units of target currency per 1 unit of source currency. */
    @Column(name = "rate", precision = 19, scale = 8, nullable = false)
    private BigDecimal rate;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** Where the rate came from (publication, treasury memo, provider reference). */
    @Column(name = "rate_source", length = 255, nullable = false)
    private String rateSource;

    @Column(name = "approved_by", length = 100, nullable = false)
    private String approvedBy;

    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        validate();
    }

    @PreUpdate
    void onUpdate() {
        validate();
    }

    /** Rejects rows that could be ambiguous or unusable as a rate. */
    void validate() {
        if (!isCurrencyCode(sourceCurrency) || !isCurrencyCode(targetCurrency)) {
            throw new IllegalStateException("Exchange rate currencies must be upper-case ISO codes (e.g. INR, USD).");
        }
        if (sourceCurrency.equals(targetCurrency)) {
            throw new IllegalStateException("Exchange rate source and target currency must differ.");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalStateException("Exchange rate must be positive.");
        }
        if (effectiveDate == null) {
            throw new IllegalStateException("Exchange rate effective date is required.");
        }
        if (rateSource == null || rateSource.isBlank() || approvedBy == null || approvedBy.isBlank()
                || approvedAt == null) {
            throw new IllegalStateException("Exchange rate source, approver and approval time are required.");
        }
    }

    private static boolean isCurrencyCode(String code) {
        return code != null && code.matches("[A-Z]{3}");
    }
}
