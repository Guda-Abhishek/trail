package com.AccountReceivableManagement.dto.billing_data_acquisition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Epic 2's own consumer-side view of an approved timesheet entry, as
 * returned by {@code TimesheetIntegration}. Mirrors what the TMS exposes,
 * defined locally so Epic 2 never depends on TMS's internal DTOs.
 *
 * {@code hourlyRate} is populated by the Strategy from the Billing
 * Configuration (Epic 1), not by the TMS integration — TMS owns
 * operational data only, never commercial data.
 *
 * {@code workDate} and {@code role} come from TMS and are carried through
 * for UI display and ROLE_BASED rate resolution respectively.
 * {@code approvalStatus} is carried for UI display ("Approval Status" column).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimesheetDto {

    private Long resourceId;

    /** Employee display name — shown as "Employee" column in Labor Charges Preview. */
    private String resourceName;

    /** TMS timesheet ID — stored as sourceReferenceId for audit traceability. */
    private String sourceReferenceId;

    /** Date work was performed — shown as "Work Date" column in Labor Charges Preview. */
    private LocalDate workDate;

    /** Approved billable hours. */
    private BigDecimal hours;

    /**
     * Hourly rate — NOT from TMS. Populated by TimeAndMaterialBillingStrategy
     * from the Billing Configuration after the TMS call returns.
     */
    private BigDecimal hourlyRate;

    /**
     * Project role (e.g. "Developer", "Tester") — from TMS.
     * Used by ROLE_BASED billing to look up the per-role rate from Billing Config.
     */
    private String role;

    /** TMS approval status — always "APPROVED" per TMS contract. Shown in UI. */
    private String approvalStatus;

    private boolean approved;

    private boolean billable;

    // ---- Standard Rate Schedule calculation, set by TimeAndMaterialBillingStrategy ----
    // hourlyRate above is the effective NET rate (base rate after any discount period).

    /** Standard base rate before any discount. */
    private BigDecimal baseRate;

    /** Billing configuration currency; the rate, discount and net rate are all in it. */
    private String currencyCode;

    /** Billing unit of the rate (always HOURLY for hours-based billing). */
    private String ratePeriod;

    private String discountType;

    private BigDecimal discountValue;

    private BigDecimal discountAmount;

    private String discountPeriodId;

    private String discountPeriodName;

    private LocalDate discountPeriodStart;

    private LocalDate discountPeriodEnd;

    /** Null (no discount period applied) means included. */
    private Boolean includeInInvoice;
}

