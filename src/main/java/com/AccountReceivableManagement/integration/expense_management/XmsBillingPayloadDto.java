package com.AccountReceivableManagement.integration.expense_management;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Billing payload of one line item. Every field is optional at the
 * deserialisation level - which fields AR requires is decided by the
 * validator, not by this DTO.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XmsBillingPayloadDto {

    private String payloadVersion;
    private String lineItemId;
    private String reportNumber;
    private String employeeId;
    private LocalDate expenseDate;
    private String category;
    private String description;
    private String merchantName;
    private String projectCode;
    private String projectName;
    private String clientName;
    /** Authoritative client-billable amount in base currency (= base amount - base recoverable tax). */
    private BigDecimal costBasis;
    private BigDecimal grossAmount;
    private BigDecimal inputTaxAmount;
    private BigDecimal recoverableTaxAmount;
    private String taxCode;
    private String currency;
    private String originalCurrency;
    private BigDecimal originalAmount;
    private List<String> receiptIds = new ArrayList<>();
    private String handoffStatus;
    private String invoiceReference;
}
