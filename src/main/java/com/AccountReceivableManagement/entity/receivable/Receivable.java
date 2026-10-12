package com.AccountReceivableManagement.entity.receivable;

import com.AccountReceivableManagement.entity.invoice_generation.Invoice;
import com.AccountReceivableManagement.entity_enums.receivable.ReceivableStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The amount a client owes for exactly one dispatched {@link Invoice}.
 * Created when the invoice is first successfully sent to the client, and
 * holds a point-in-time copy of the invoice's number, client, project,
 * dates and grand total - never re-derived from billing configuration,
 * schedules or tax data. Billing-type agnostic: every billing flow that
 * produces an Invoice reaches a Receivable the same way.
 * <p>
 * The invoice is mapped as a real association (rather than the plain UUID
 * columns used by the invoice audit tables) so the schema carries an
 * enforced foreign key. It is {@code @ManyToOne} only so Hibernate does not
 * generate a second, auto-named unique key alongside
 * {@code uk_receivable_invoice}; that named constraint is what makes the
 * relationship one-to-one, and is the final guard against two receivables
 * for the same invoice.
 */
@Entity
@Table(
        name = "receivable",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_receivable_invoice",
                        columnNames = {"invoice_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_receivable_client",
                        columnList = "client_id"
                ),
                @Index(
                        name = "idx_receivable_status",
                        columnList = "status"
                )
        }
)
/*
 * Database-level guard for "outstanding balance never negative". MySQL
 * enforces CHECK constraints from 8.0.16; ddl-auto=update only emits it when
 * it creates the table, so an already-existing receivable table needs the
 * ALTER in sql/receivable.sql.
 */
@Check(name = "chk_receivable_outstanding_non_negative", constraints = "outstanding_balance >= 0")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Receivable {

    /**
     * Name of the unique constraint on {@code invoice_id} - used to
     * recognise a concurrent duplicate insert among other integrity errors.
     */
    public static final String INVOICE_UNIQUE_CONSTRAINT = "uk_receivable_invoice";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "receivable_id")
    private UUID receivableId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "invoice_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_receivable_invoice")
    )
    private Invoice invoice;

    @Column(name = "invoice_number", nullable = false, updatable = false, length = 30)
    private String invoiceNumber;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "client_name", length = 255)
    private String clientName;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "project_name", length = 255)
    private String projectName;

    @Column(name = "currency_code", length = 10)
    private String currencyCode;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    /**
     * Copied from {@link Invoice#getDueDate()}, which is {@code null} when
     * the invoice's payment term could not be resolved - never guessed here.
     */
    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    /**
     * The dispatched invoice's grand total - fixed for the life of the
     * receivable.
     */
    @Column(
            name = "invoice_amount",
            nullable = false,
            updatable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal invoiceAmount;

    /**
     * Amount still owed. Starts equal to {@code invoiceAmount}; may only be
     * lowered by the future payment/adjustment functionality, and can never
     * be negative (enforced on every insert/update below and by
     * {@code chk_receivable_outstanding_non_negative}).
     */
    @Column(
            name = "outstanding_balance",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal outstandingBalance;

    /*
     * VARCHAR rather than Hibernate's default native MySQL ENUM, for the
     * same reason as Invoice.status: ddl-auto=update never widens an
     * existing ENUM's value list as this lifecycle grows.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR(20)"
    )
    private ReceivableStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        requireNonNegativeBalance();
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        requireNonNegativeBalance();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Model-level guard for the over-credit rule, so no write path - present
     * or future - can persist a negative balance, independent of whether the
     * database enforces the CHECK constraint.
     */
    private void requireNonNegativeBalance() {
        if (outstandingBalance != null && outstandingBalance.signum() < 0) {
            throw new IllegalStateException("Receivable outstanding balance cannot be negative.");
        }
    }
}
