package com.AccountReceivableManagement.integration.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto;

import java.time.LocalDate;
import java.util.List;

/**
 * Isolates billing acquisition from how client-billable expenses are
 * obtained (currently XMS). Returns only validated lines; invalid or
 * inconsistent data fails the acquisition with an actionable error.
 */
public interface ExpenseIntegration {

    /**
     * @param expectedCurrency currency of the billing configuration; every
     *                         expense must already be in it (no conversion in AR)
     */
    List<ExpenseLineDto> getBillableExpenses(Long projectId, LocalDate periodStart,
                                             LocalDate periodEnd, String expectedCurrency);
}
