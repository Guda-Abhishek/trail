package com.AccountReceivableManagement.repo.receivable;

import com.AccountReceivableManagement.entity.receivable.Receivable;
import com.AccountReceivableManagement.entity_enums.receivable.ReceivableStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReceivableRepository extends JpaRepository<Receivable, UUID> {

    /**
     * The receivable of one invoice, if it has been created - at most one,
     * enforced by {@code uk_receivable_invoice}.
     */
    @Query("SELECT r FROM Receivable r WHERE r.invoice.invoiceId = :invoiceId")
    Optional<Receivable> findByInvoiceId(@Param("invoiceId") UUID invoiceId);

    /**
     * Backs {@code GET /api/v1/receivables/outstanding}. "Outstanding" is
     * decided by the balance alone ({@code outstandingBalance > 0}), never
     * by status, so a zero-balance receivable is excluded whatever its
     * status and a future status cannot hide money still owed. Every filter
     * is optional and only narrows the result. Earliest due first (no due
     * date last), then oldest invoice first.
     */
    @Query(
            "SELECT r FROM Receivable r "
                    + "WHERE r.outstandingBalance > 0 "
                    + "AND (:clientId IS NULL OR r.clientId = :clientId) "
                    + "AND (:projectId IS NULL OR r.projectId = :projectId) "
                    + "AND (:status IS NULL OR r.status = :status) "
                    + "ORDER BY r.dueDate ASC NULLS LAST, r.invoiceDate ASC"
    )
    List<Receivable> findOutstanding(
            @Param("clientId") UUID clientId,
            @Param("projectId") Long projectId,
            @Param("status") ReceivableStatus status
    );
}
