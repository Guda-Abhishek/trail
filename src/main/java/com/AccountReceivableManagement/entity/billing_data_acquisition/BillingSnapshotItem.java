package com.AccountReceivableManagement.entity.billing_data_acquisition;

import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One acquired, already-validated billing line within a {@link BillingSnapshot}
 * — a timesheet entry today, a milestone/expense/retainer charge in later
 * stories. {@code sourceReferenceId} traces the line back to its origin-system
 * record; which system that is follows from {@code itemType}.
 */
@Entity
@Table(name = "billing_snapshot_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingSnapshotItem {

    @Id
    @GeneratedValue
    @Column(name = "billing_snapshot_item_id")
    private UUID billingSnapshotItemId;

    @ManyToOne
    @JoinColumn(name = "billing_snapshot_id", nullable = false)
    private BillingSnapshot billingSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 30)
    private BillingItemType itemType;

    @Column(name = "item_name", length = 255)
    private String itemName;

    @Column(name = "source_reference_id", length = 100)
    private String sourceReferenceId;

    /**
     * TMS employee display name ({@code TimesheetDto.resourceName}) —
     * populated only for TIME_ENTRY lines, where it is a genuine value
     * from the source system. {@code null} for line types with no
     * resource concept (e.g. FIXED_PRICE, tool/software charges).
     */
    @Column(name = "resource_name", length = 255)
    private String resourceName;

    @Column(name = "quantity", precision = 19, scale = 2)
    private BigDecimal quantity;

    @Column(name = "rate", precision = 19, scale = 2)
    private BigDecimal rate;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    /** Date work was performed — sourced from TMS. Shown as "Work Date" column in UI. */
    @Column(name = "work_date")
    private LocalDate workDate;

    /**
     * TMS approval status (always "APPROVED" per TMS contract).
     * Shown as "Approval Status" column in the Labor Charges Preview table.
     */
    @Column(name = "approval_status", length = 30)
    private String approvalStatus;

    /** Project role sourced from TMS — used for ROLE_BASED rate lookup. */
    @Column(name = "role", length = 100)
    private String role;

    /** Originating system for lines not sourced from TMS (e.g. "XMS"); null for legacy lines. */
    @Column(name = "source_system", length = 20)
    private String sourceSystem;

    /**
     * Frozen JSON copy of the source record's audit/reconciliation fields
     * (for XMS expenses: report number, employee, category, tax and original
     * currency details, receipt ids, payload version). Never used to
     * recompute amounts or output tax.
     */
    @Column(name = "source_metadata", columnDefinition = "TEXT")
    private String sourceMetadata;

    /**
     * Handoff event identity (XMS {@code syncId}) of an EXPENSE line; null for other lines and for lines
     * created before this field existed. Together with {@code sourceReferenceId} (the lineItemId) it
     * distinguishes a repeat of the same handoff from a correction.
     */
    @Column(name = "source_event_id", length = 100)
    private String sourceEventId;

    // ---- expense currency-conversion audit (frozen at acquisition; null when no conversion) ----
    // "amount" above stays the billed amount in the snapshot currency.

    /** XMS costBasis before conversion. */
    @Column(name = "source_amount", precision = 19, scale = 4)
    private BigDecimal sourceAmount;

    @Column(name = "source_currency", length = 10)
    private String sourceCurrency;

    /** Billing (snapshot) currency the amount was converted to. */
    @Column(name = "target_currency", length = 10)
    private String targetCurrency;

    /** Units of target currency per 1 unit of source currency. */
    @Column(name = "exchange_rate", precision = 19, scale = 8)
    private BigDecimal exchangeRate;

    @Column(name = "exchange_rate_date")
    private LocalDate exchangeRateDate;

    @Column(name = "exchange_rate_source", length = 255)
    private String exchangeRateSource;

    // ---- T&M Standard Rate Schedule calculation (frozen at acquisition; null on older lines) ----
    // "rate" above is the effective NET rate and "amount" the final line amount (quantity x net rate).

    @Column(name = "base_rate", precision = 19, scale = 2)
    private BigDecimal baseRate;

    /** Billing configuration currency the rates and amounts are in. */
    @Column(name = "rate_currency", length = 10)
    private String rateCurrency;

    @Column(name = "rate_period", length = 20)
    private String ratePeriod;

    @Column(name = "discount_type", length = 20)
    private String discountType;

    @Column(name = "discount_value", precision = 19, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "discount_amount", precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "discount_period_id", length = 36)
    private String discountPeriodId;

    @Column(name = "discount_period_name", length = 150)
    private String discountPeriodName;

    @Column(name = "discount_period_start")
    private LocalDate discountPeriodStart;

    @Column(name = "discount_period_end")
    private LocalDate discountPeriodEnd;

    /**
     * Inclusion decision. {@code false} keeps the line for audit but it is excluded from the snapshot totals,
     * tax base and invoice items. Null (older lines, expenses) means included.
     */
    @Column(name = "include_in_invoice")
    private Boolean includeInInvoice;
}
