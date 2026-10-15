package com.AccountReceivableManagement.dto.projectbilling_config;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectResponseDto {

    /**
     * AR's project key. In AR this IS the PMS project id (project_master_reference is keyed by
     * pms_project_id and billing_configuration.project_id references it); kept for compatibility.
     */
    private Long projectId;

    /** The numeric PMS project id, explicitly - the identifier XMS expenses are filtered by. */
    private Long pmsProjectId;

    private String projectName;

    private String projectCode;

    private String projectDuration;

    private BigDecimal projectBudget;

    private String projectBudgetCurrency;

    private String primaryLocation;

    private String countryCode;

    private String email;

    private String phoneNumber;

//    private Long projectManager;
}
