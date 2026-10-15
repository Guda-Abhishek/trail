package com.AccountReceivableManagement.repo.billing_data_acquisition;

import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence for {@link BillingSnapshotItem}. Items are only ever
 * created and read through their parent {@code BillingSnapshot}.
 */
@Repository
public interface BillingSnapshotItemRepository extends JpaRepository<BillingSnapshotItem, UUID> {

    boolean existsBySourceReferenceId(String sourceReferenceId);

    /**
     * The expense lines of this lineItemId already sitting on a live (non-cancelled) snapshot other than
     * the one for this project and period - the project+period snapshot is unique, so it is the one being
     * (re)built. Returned (not just counted) so the caller can tell a repeat of the same handoff from a
     * correction, and whether the billed snapshot is already invoiced.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT i FROM BillingSnapshotItem i
            WHERE i.itemType = com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType.EXPENSE
              AND i.sourceReferenceId = :lineItemId
              AND i.billingSnapshot.status <> com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus.CANCELLED
              AND NOT (i.billingSnapshot.projectId = :projectId
                       AND i.billingSnapshot.billingPeriodStart = :periodStart
                       AND i.billingSnapshot.billingPeriodEnd = :periodEnd)
            """)
    java.util.List<BillingSnapshotItem> findExpenseItemsInOtherSnapshots(
            @org.springframework.data.repository.query.Param("lineItemId") String lineItemId,
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("periodStart") LocalDate periodStart,
            @org.springframework.data.repository.query.Param("periodEnd") LocalDate periodEnd);

    boolean existsBySourceReferenceIdAndBillingSnapshot_BillingPeriodStartAndBillingSnapshot_BillingPeriodEnd(
            String sourceReferenceId,
            LocalDate billingPeriodStart,
            LocalDate billingPeriodEnd);
}
