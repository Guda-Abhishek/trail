package com.AccountReceivableManagement.dto.billing_data_acquisition;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingAcquisitionResultDto {

    private UUID billingConfigurationId;

    @Builder.Default
    private List<TimesheetDto> timesheets = new ArrayList<>();

    /** True when expenses were looked up in XMS for this acquisition (expense billing on). */
    private boolean expensesChecked;

    /** Validated client-billable expenses (XMS); empty when none or expense billing is off. */
    @Builder.Default
    private List<ExpenseLineDto> expenses = new ArrayList<>();
}
