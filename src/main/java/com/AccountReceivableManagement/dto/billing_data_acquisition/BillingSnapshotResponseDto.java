package com.AccountReceivableManagement.dto.billing_data_acquisition;

import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Response returned for a Billing Snapshot — by {@code POST /billing-snapshots}
 * on creation and by {@code GET /billing-snapshots/{snapshotId}} on retrieval.
 * Exposes only the snapshot summary defined by the current API contract;
 * line-item detail is a later story.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillingSnapshotResponseDto {

    /**
     * System-generated identifier of the Billing Snapshot.
     */
    private UUID snapshotId;

    /**
     * Acquisition status determined by the snapshot engine (e.g. READY | PARTIALLY_READY).
     */
    private String acquisitionStatus;

    /**
     * Human-readable, business-facing identifier (e.g. {@code BS-20260724153015}).
     */
    private String snapshotNumber;

    /**
     * Project this snapshot was acquired for.
     */
    private Long projectId;

    /**
     * Client this snapshot was acquired for.
     */
    private UUID clientId;

    /**
     * Billing Type master reference recorded on the snapshot at acquisition time.
     */
    private UUID billingTypeId;

    /**
     * Billing Type master name, resolved from Epic 1 at read time.
     */
    private String billingTypeName;

    /**
     * Currency master reference recorded on the snapshot at acquisition time.
     */
    private UUID currencyId;

    /**
     * ISO currency code (e.g. {@code USD}), resolved from Epic 1 at read time.
     */
    private String currencyCode;

    /**
     * Payment Terms master reference recorded on the snapshot at acquisition time.
     */
    private UUID paymentTermId;

    /**
     * Payment Terms master name, resolved from Epic 1 at read time.
     */
    private String paymentTermName;

    /**
     * Billing Frequency master reference recorded on the snapshot at acquisition time.
     */
    private UUID billingFrequencyId;

    /**
     * Billing Frequency master name, resolved from Epic 1 at read time.
     */
    private String billingFrequencyName;

    /**
     * Tax Region master reference recorded on the snapshot at acquisition time.
     */
    private UUID taxRegionId;

    /**
     * Tax region code, resolved from Epic 1 at read time.
     */
    private String taxRegionCode;

    /**
     * Source tax jurisdiction code (our company's tax region), frozen at snapshot creation time.
     */
    private String sourceTaxJurisdictionCode;

    /**
     * Destination tax jurisdiction code (client's tax region), frozen at snapshot creation time.
     */
    private String destinationTaxJurisdictionCode;

    /**
     * Start date of the billing period this snapshot covers.
     */
    private LocalDate billingPeriodStart;

    /**
     * End date of the billing period this snapshot covers.
     */
    private LocalDate billingPeriodEnd;

    /**
     * Sum of acquired billing items before expenses. Defaults to zero
     * rather than null when a billing type has no items to sum.
     */
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    /**
     * Sum of acquired expense items, if any. Defaults to zero rather than
     * null when there are no expenses (e.g. Story 2.1, Time & Material only).
     */
    @Builder.Default
    private BigDecimal expenseAmount = BigDecimal.ZERO;

    /**
     * Subtotal plus expense amount — the total this snapshot bills for.
     */
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * Current lifecycle status of the snapshot.
     */
    private BillingSnapshotStatus status;

    /**
     * True when {@code POST /billing-snapshots} returned a snapshot that was
     * already persisted for the project and billing period instead of
     * creating one; false when it was just created. Omitted on retrieval.
     */
    private Boolean existingSnapshot;

    /**
     * Timesheet line items acquired from TMS — returned so the UI can
     * immediately render the Labor Charges Preview table without a
     * second round-trip. Each entry mirrors one TMS timesheet row with
     * the commercial rate merged in from the Billing Configuration.
     */
    @Builder.Default
    private List<TimesheetLineItemDto> timesheets = new ArrayList<>();

    /**
     * Persisted client-billable expense lines (XMS) of this snapshot, kept
     * separate from labor {@code timesheets}. Always present; empty when the
     * snapshot has no expenses. Their {@code costBasis} values sum to
     * {@code expenseAmount}.
     */
    @Builder.Default
    private List<ExpenseLineItemDto> expenses = new ArrayList<>();

    /**
     * One expense row. {@code costBasis} is the billed amount in the snapshot currency (as supplied by XMS,
     * or converted from {@code sourceAmount}); tax fields are audit-only and not exposed here. Everything is
     * read back from what was persisted at acquisition - nothing is recalculated.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExpenseLineItemDto {
        /** XMS lineItemId (the idempotency key) - the identity of the underlying expense. */
        private String sourceReferenceId;
        /** Same value as {@link #sourceReferenceId}, named explicitly for clients. */
        private String lineItemId;
        private LocalDate expenseDate;
        private String category;
        private String description;
        private String reportNumber;
        private String currency;
        private BigDecimal costBasis;
        /** Origin system, "XMS". */
        private String sourceSystem;
        /** XMS handoff event (syncId) this line was billed from. */
        private String syncId;
        /** When XMS recorded that handoff event (from the frozen acquisition metadata); null if not stored. */
        private java.time.LocalDateTime handedOffAt;
        private String handoffRemarks;
        /** syncIds of earlier handoff events of this line item that this one superseded (empty if none). */
        @Builder.Default
        private List<String> supersededSyncIds = new ArrayList<>();
        /** The employee's original expense currency/amount (audit only, never billed); null when not stored. */
        private String originalCurrency;
        private BigDecimal originalAmount;
        /**
         * True only when a currency conversion was performed and persisted for this line (the exchange-rate
         * audit fields below are set). Never inferred from a currency mismatch.
         */
        private boolean conversionApplied;
        private BigDecimal sourceAmount;
        private String sourceCurrency;
        private BigDecimal exchangeRate;
        private LocalDate exchangeRateDate;
        private String exchangeRateSource;
    }


    /**
     * One row in the Labor Charges Preview table shown in AcquireDataStep.jsx.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimesheetLineItemDto {
        /** Shown as "Employee" column. */
        private String employee;
        /** Shown as "Work Date" column (ISO YYYY-MM-DD). */
        private LocalDate workDate;
        /** Shown as "Hours" column. */
        private BigDecimal hours;
        /** Shown as "Rate" column — sourced from AR Billing Config, not TMS. */
        private BigDecimal rate;
        /** Shown as "Amount" column = hours × rate. */
        private BigDecimal amount;
        /** Shown as "Approval Status" column — always "APPROVED" per TMS contract. */
        private String approvalStatus;
        /** TMS source reference id for audit. */
        private String sourceReferenceId;
        /** Project role — used for ROLE_BASED billing rate lookup. */
        private String role;
        /** Standard base rate before discount ("rate" above is the net rate); null on older lines. */
        private BigDecimal baseRate;
        private String currencyCode;
        private String ratePeriod;
        private String discountType;
        private BigDecimal discountValue;
        private BigDecimal discountAmount;
        private String discountPeriodName;
        private LocalDate discountPeriodStart;
        private LocalDate discountPeriodEnd;
        /** false = kept for audit, excluded from totals, tax and invoice. */
        private Boolean includeInInvoice;
    }
}
