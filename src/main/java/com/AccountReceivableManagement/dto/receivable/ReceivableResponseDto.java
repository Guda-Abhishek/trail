package com.AccountReceivableManagement.dto.receivable;

import com.AccountReceivableManagement.entity_enums.receivable.ReceivableStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceivableResponseDto {

    private UUID receivableId;

    private UUID invoiceId;

    private String invoiceNumber;

    private UUID clientId;

    private String clientName;

    private Long projectId;

    private String projectName;

    private String currencyCode;

    private LocalDate invoiceDate;

    private LocalDate dueDate;

    private BigDecimal invoiceAmount;

    private BigDecimal outstandingBalance;

    private ReceivableStatus status;

    /**
     * Age of the unpaid invoice: today - invoiceDate, in days (never
     * negative). {@code null} once nothing is outstanding.
     */
    private Long daysOutstanding;

    /**
     * Days past the due date: today - dueDate, floored at 0 (0 = not yet
     * overdue). {@code null} when the invoice has no due date or nothing is
     * outstanding.
     */
    private Long daysOverdue;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
