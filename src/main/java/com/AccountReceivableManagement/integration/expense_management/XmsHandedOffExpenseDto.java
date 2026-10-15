package com.AccountReceivableManagement.integration.expense_management;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One record of {@code data.content} from the handed-off API. One record is one
 * handoff <em>event</em> ({@code syncId}); the same {@code lineItemId} can be
 * handed off more than once, and a later event is a correction (reason in
 * {@code remarks}), not a new charge. {@code pmsProjectId} (numeric) and
 * {@code resolvedClientId} are the authoritative project/client identifiers;
 * {@code projectCode}/{@code projectName} are mutable display values and are never
 * used for attribution. {@code invoiceReference} is null while the expense has
 * not been invoiced yet.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmsHandedOffExpenseDto {

    private String syncId;
    private String lineItemId;
    private String reportId;
    private String reportNumber;
    private String employeeId;
    private LocalDate expenseDate;
    private String categoryName;
    private String description;
    private BigDecimal baseAmount;
    private String baseCurrencyCode;
    /** Authoritative client-billable amount in base currency. */
    private BigDecimal costBasis;
    private BigDecimal recoverableTaxAmount;
    private String taxCode;
    /** Numeric PMS project id - the authoritative project identifier. */
    private Long pmsProjectId;
    private String projectCode;
    private String projectName;
    /** RMS client UUID (as text) - the authoritative client identifier. */
    private String resolvedClientId;
    private String resolvedClientName;
    private String invoiceReference;
    private LocalDateTime handedOffAt;
    private String remarks;
}
