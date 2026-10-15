package com.AccountReceivableManagement.repo.currency;

import com.AccountReceivableManagement.entity.currency.ExchangeRateRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateRecordRepository extends JpaRepository<ExchangeRateRecord, String>, JpaSpecificationExecutor<ExchangeRateRecord> {

    /** The single rate for the exact pair and date (the unique key guarantees at most one). */
    Optional<ExchangeRateRecord> findBySourceCurrencyAndTargetCurrencyAndEffectiveDate(
            String sourceCurrency, String targetCurrency, LocalDate effectiveDate);

    List<ExchangeRateRecord> findAllByOrderByEffectiveDateDescCreatedAtDesc();

    @Query("SELECT r FROM ExchangeRateRecord r WHERE " +
           "(:sourceCurrency IS NULL OR r.sourceCurrency = :sourceCurrency) AND " +
           "(:targetCurrency IS NULL OR r.targetCurrency = :targetCurrency) AND " +
           "(:effectiveDate IS NULL OR r.effectiveDate = :effectiveDate) " +
           "ORDER BY r.effectiveDate DESC, r.createdAt DESC")
    List<ExchangeRateRecord> findFiltered(
            @Param("sourceCurrency") String sourceCurrency,
            @Param("targetCurrency") String targetCurrency,
            @Param("effectiveDate") LocalDate effectiveDate);
}
