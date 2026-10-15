package com.AccountReceivableManagement.dto.billing_data_acquisition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AR's own view of one validated, billable XMS expense line. {@code costBasis}
 * is the authoritative client-billable amount (XMS base currency, net of
 * recoverable tax). When XMS's currency differs from the billing currency it
 * is replaced by the converted amount in the billing currency and the
 * originals move to the {@code source*}/{@code exchangeRate*} fields. It is the
 * only amount AR bills. The tax and original
 * currency fields are carried solely for traceability and audit - they are
 * never used as output tax.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseLineDto {

    /** XMS lineItemId - the expense idempotency key. */
    private String lineItemId;
    private String payloadVersion;
    private String reportNumber;
    private String employeeId;
    private LocalDate expenseDate;
    private String category;
    private String description;
    private String merchantName;
    private String projectCode;
    private String projectName;
    private String clientName;
    private BigDecimal costBasis;
    private String currency;
    private BigDecimal grossAmount;
    private BigDecimal inputTaxAmount;
    private BigDecimal recoverableTaxAmount;
    private String taxCode;
    private String originalCurrency;
    private BigDecimal originalAmount;

    // ---- handoff identity / correction audit (XMS handoff event of this line) ----
    /** syncId of the handoff event this line was billed from. */
    private String syncId;
    private LocalDateTime handedOffAt;
    /** XMS remarks - the correction reason when this event corrected an earlier handoff. */
    private String handoffRemarks;
    /** syncIds of earlier handoff events of the same lineItemId that this event superseded. */
    @Builder.Default
    private List<String> supersededSyncIds = new ArrayList<>();

    // ---- currency conversion audit (all null when no conversion was needed) ----
    // costBasis/currency above are the BILLED amount and currency (billing
    // currency). When the XMS currency differed, these keep what XMS supplied
    // and how it was converted; they are never recomputed after acquisition.
    /** XMS-authoritative costBasis before conversion. */
    private BigDecimal sourceAmount;
    /** Currency of {@link #sourceAmount} (XMS payload currency). */
    private String sourceCurrency;
    /** Units of billing currency per 1 unit of source currency. */
    private BigDecimal exchangeRate;
    private LocalDate exchangeRateDate;
    /** Audit reference of the approved rate (provider/table row). */
    private String exchangeRateSource;

    @Builder.Default
    private List<String> receiptIds = new ArrayList<>();
}
