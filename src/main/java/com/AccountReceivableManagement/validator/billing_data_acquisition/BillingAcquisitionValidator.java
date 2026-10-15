package com.AccountReceivableManagement.validator.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingAcquisitionResultDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.TimesheetDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.ValidationResultDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates acquired Time &amp; Material data before it reaches the Builder.
 * Fail-fast: any rule violation immediately fails the whole acquisition —
 * invalid data is never silently dropped or cleaned up.
 */
@Component
public class BillingAcquisitionValidator {

    public ValidationResultDto validate(BillingAcquisitionResultDto acquisitionResult) {
        if (acquisitionResult == null) {
            return ValidationResultDto.failure("Billing acquisition result is missing.");
        }

        List<TimesheetDto> timesheets = acquisitionResult.getTimesheets();
        List<ExpenseLineDto> expenses = acquisitionResult.getExpenses();
        boolean hasExpenses = expenses != null && !expenses.isEmpty();
        if ((timesheets == null || timesheets.isEmpty()) && !hasExpenses) {
            return ValidationResultDto.failure(acquisitionResult.isExpensesChecked()
                    ? "No expense eligible for this project duration, and no timesheets were acquired "
                    + "for the requested billing period."
                    : "No timesheets were acquired for the requested billing period.");
        }
        if (timesheets == null) {
            timesheets = List.of();
        }

        if (hasExpenses) {
            // Defence in depth: the XMS integration already validates each line.
            Set<String> seenLineItemIds = new HashSet<>();
            for (ExpenseLineDto expense : expenses) {
                if (expense.getLineItemId() == null || expense.getLineItemId().isBlank()) {
                    return ValidationResultDto.failure("An expense line has no lineItemId.");
                }
                if (expense.getCostBasis() == null || expense.getCostBasis().signum() < 0) {
                    return ValidationResultDto.failure(
                            "Expense [" + expense.getLineItemId() + "] has an invalid costBasis.");
                }
                if (!seenLineItemIds.add(expense.getLineItemId())) {
                    return ValidationResultDto.failure(
                            "Expense [" + expense.getLineItemId() + "] is a duplicate lineItemId.");
                }
            }
        }

        Set<String> seenSourceReferenceIds = new HashSet<>();
        for (TimesheetDto timesheet : timesheets) {
            if (!timesheet.isApproved()) {
                return ValidationResultDto.failure(
                        "Timesheet [" + timesheet.getSourceReferenceId() + "] is not approved.");
            }
            if (!timesheet.isBillable()) {
                return ValidationResultDto.failure(
                        "Timesheet [" + timesheet.getSourceReferenceId() + "] is not billable.");
            }
            if (timesheet.getHours() == null || timesheet.getHours().compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResultDto.failure(
                        "Timesheet [" + timesheet.getSourceReferenceId() + "] has invalid hours.");
            }
            if (timesheet.getHourlyRate() == null || timesheet.getHourlyRate().compareTo(BigDecimal.ZERO) <= 0) {
                String configIdStr = acquisitionResult.getBillingConfigurationId() != null
                        ? acquisitionResult.getBillingConfigurationId().toString()
                        : "N/A";
                return ValidationResultDto.failure(
                        "No active hourly rate found for billing configuration " + configIdStr + " and work date " + timesheet.getWorkDate() + ".");
            }
            if (!seenSourceReferenceIds.add(timesheet.getSourceReferenceId())) {
                return ValidationResultDto.failure(
                        "Timesheet [" + timesheet.getSourceReferenceId() + "] is a duplicate source reference.");
            }
        }

        // Lines in a discount period excluded from invoicing stay on the snapshot for audit, but a snapshot
        // with nothing payable at all (no included timesheet, no expense) would become a zero-value invoice.
        boolean anyPayableTimesheet = timesheets.stream()
                .anyMatch(t -> com.AccountReceivableManagement.util.TmRateCalculator.isIncluded(t.getIncludeInInvoice()));
        if (!timesheets.isEmpty() && !anyPayableTimesheet && !hasExpenses) {
            return ValidationResultDto.failure(
                    "Every acquired timesheet falls in a discount period that is excluded from invoicing; "
                            + "there is nothing payable to bill for the requested billing period.");
        }

        return ValidationResultDto.success(acquisitionResult);
    
    }
}
