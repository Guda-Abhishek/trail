package com.AccountReceivableManagement.repo.projectbilling_config;

import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateCard;
import com.AccountReceivableManagement.entity.projectbilling_config.BillingTMRateDiscount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface BillingTMRateDiscountRepository extends JpaRepository<BillingTMRateDiscount, UUID> {

    List<BillingTMRateDiscount> findByRateCard_RateCardIdAndIsActiveTrueOrderByEffectiveFromAsc(UUID rateCardId);

    /** Active periods of the card containing the date (both bounds inclusive). */
    @Query("SELECT d FROM BillingTMRateDiscount d WHERE d.rateCard.rateCardId = :rateCardId " +
           "AND d.isActive = true AND d.effectiveFrom <= :workDate AND d.effectiveTo >= :workDate")
    List<BillingTMRateDiscount> findActiveByRateCardAndDate(
            @Param("rateCardId") UUID rateCardId,
            @Param("workDate") LocalDate workDate);

    /** Active periods of the card intersecting [from, to], optionally excluding one period (update). */
    @Query("SELECT d FROM BillingTMRateDiscount d WHERE d.rateCard.rateCardId = :rateCardId " +
           "AND d.isActive = true AND d.effectiveFrom <= :to AND d.effectiveTo >= :from " +
           "AND (:excludeId IS NULL OR d.discountId <> :excludeId)")
    List<BillingTMRateDiscount> findOverlapping(
            @Param("rateCardId") UUID rateCardId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("excludeId") UUID excludeId);

    /** Row lock on the parent rate card so concurrent writers cannot both pass the overlap check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BillingTMRateCard r WHERE r.rateCardId = :rateCardId")
    java.util.Optional<BillingTMRateCard> lockRateCard(@Param("rateCardId") UUID rateCardId);
}
