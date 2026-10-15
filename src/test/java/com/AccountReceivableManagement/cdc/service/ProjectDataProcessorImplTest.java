package com.AccountReceivableManagement.cdc.service;

import com.AccountReceivableManagement.cdc.mapping.ProjectCdcMappingRegistry;
import com.AccountReceivableManagement.cdc.parsing.CdcValueConverter;
import com.AccountReceivableManagement.cdc.payload.CdcEventPayload;
import com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference;
import com.AccountReceivableManagement.repo.project.ProjectMasterReferenceRepository;
import com.AccountReceivableManagement.service_interface.client.ClientBudgetSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectDataProcessorImplTest {

    @Mock
    private ProjectMasterReferenceRepository projectMasterReferenceRepository;

    @Mock
    private CdcValueConverter valueConverter;

    @Mock
    private ClientBudgetSummaryService clientBudgetSummaryService;

    @InjectMocks
    private ProjectDataProcessorImpl projectDataProcessor;

    private CdcEventPayload createPayload;
    private Map<String, Object> afterData;
    private UUID clientId;

    @BeforeEach
    void setUp() {
        clientId = UUID.randomUUID();
        afterData = new HashMap<>();
        afterData.put("id", 35L);
        afterData.put("name", "Project Deccan");
        afterData.put("project_key", "PD-424");
        afterData.put("client_id", clientId.toString());
        afterData.put("project_budget", new BigDecimal("67000.00"));
        afterData.put("project_budget_currency", "USD");

        // The converter is a mock, so without a default every mapped field (including client_id) would be
        // converted to null and overwrite the entity's clientId. Default: client_id -> UUID, others as-is.
        lenient().when(valueConverter.convertValue(any(), any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            com.AccountReceivableManagement.cdc.mapping.ColumnMapping mapping = invocation.getArgument(1);
            if (value instanceof String && mapping != null && "clientId".equals(mapping.getTargetField())) {
                return UUID.fromString((String) value);
            }
            return value;
        });
        // process() re-reads the project after create/update to find the client whose budget to refresh.
        lenient().when(projectMasterReferenceRepository.findBypmsProjectId(35L)).thenReturn(Optional.of(
                ProjectMasterReference.builder().pmsProjectId(35L).clientId(clientId).build()));

        createPayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("c")
                .after(afterData)
                .build();
    }

    @Test
    void testProcess_CreateOperation_Success() {
        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.existsBypmsProjectId(35L)).thenReturn(false);
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(clientBudgetSummaryService).refreshClientBudget(clientId);

        projectDataProcessor.process(createPayload);

        verify(projectMasterReferenceRepository).save(any(ProjectMasterReference.class));
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_CreateOperation_ProjectAlreadyExists() {
        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.existsBypmsProjectId(35L)).thenReturn(true);

        projectDataProcessor.process(createPayload);

        verify(projectMasterReferenceRepository, never()).save(any());
        verify(clientBudgetSummaryService, never()).refreshClientBudget(any());
    }

    @Test
    void testProcess_CreateOperation_BudgetRefreshFails_ProjectStillSaved() {
        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.existsBypmsProjectId(35L)).thenReturn(false);
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("Budget refresh failed"))
                .when(clientBudgetSummaryService).refreshClientBudget(clientId);

        assertDoesNotThrow(() -> projectDataProcessor.process(createPayload));

        verify(projectMasterReferenceRepository).save(any(ProjectMasterReference.class));
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_CreateOperation_NullAfterData() {
        CdcEventPayload payload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("c")
                .after(null)
                .build();

        projectDataProcessor.process(payload);

        verify(projectMasterReferenceRepository, never()).save(any());
        verify(clientBudgetSummaryService, never()).refreshClientBudget(any());
    }

    @Test
    void testProcess_CreateOperation_NullProjectId() {
        afterData.remove("id");
        createPayload.setAfter(afterData);

        when(valueConverter.convertValue(null, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> projectDataProcessor.process(createPayload));
    }

    @Test
    void testProcess_UpdateOperation_Success() {
        CdcEventPayload updatePayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("u")
                .after(afterData)
                .build();

        ProjectMasterReference existingProject = ProjectMasterReference.builder()
                .pmsProjectId(35L)
                .projectName("Old Name")
                .clientId(clientId)
                .projectBudget(new BigDecimal("50000.00"))
                .projectBudgetCurrency("USD")
                .build();

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.findBypmsProjectId(35L))
                .thenReturn(Optional.of(existingProject));
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(clientBudgetSummaryService).refreshClientBudget(clientId);

        projectDataProcessor.process(updatePayload);

        verify(projectMasterReferenceRepository).save(any(ProjectMasterReference.class));
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_UpdateOperation_CurrencyChange() {
        afterData.put("project_budget_currency", "INR");
        CdcEventPayload updatePayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("u")
                .after(afterData)
                .build();

        ProjectMasterReference existingProject = ProjectMasterReference.builder()
                .pmsProjectId(35L)
                .projectName("Project Deccan")
                .clientId(clientId)
                .projectBudget(new BigDecimal("67000.00"))
                .projectBudgetCurrency("USD")
                .build();

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.findBypmsProjectId(35L))
                .thenReturn(Optional.of(existingProject));
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(clientBudgetSummaryService).refreshClientBudget(clientId);

        projectDataProcessor.process(updatePayload);

        verify(projectMasterReferenceRepository).save(argThat(project -> 
                "INR".equals(project.getProjectBudgetCurrency())));
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_UpdateOperation_BudgetRefreshFails_ProjectStillUpdated() {
        CdcEventPayload updatePayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("u")
                .after(afterData)
                .build();

        ProjectMasterReference existingProject = ProjectMasterReference.builder()
                .pmsProjectId(35L)
                .projectName("Old Name")
                .clientId(clientId)
                .build();

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.findBypmsProjectId(35L))
                .thenReturn(Optional.of(existingProject));
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("Budget refresh failed"))
                .when(clientBudgetSummaryService).refreshClientBudget(clientId);

        assertDoesNotThrow(() -> projectDataProcessor.process(updatePayload));

        verify(projectMasterReferenceRepository).save(any(ProjectMasterReference.class));
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_DeleteOperation_Success() {
        CdcEventPayload deletePayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("d")
                .before(afterData)
                .build();

        ProjectMasterReference existingProject = ProjectMasterReference.builder()
                .pmsProjectId(35L)
                .projectName("Project Deccan")
                .clientId(clientId)
                .projectBudget(new BigDecimal("67000.00"))
                .projectBudgetCurrency("USD")
                .build();

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.findBypmsProjectId(35L))
                .thenReturn(Optional.of(existingProject));
        doNothing().when(clientBudgetSummaryService).refreshClientBudget(clientId);

        projectDataProcessor.process(deletePayload);

        verify(projectMasterReferenceRepository).delete(existingProject);
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_DeleteOperation_BudgetRefreshFails_ProjectStillDeleted() {
        CdcEventPayload deletePayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("d")
                .before(afterData)
                .build();

        ProjectMasterReference existingProject = ProjectMasterReference.builder()
                .pmsProjectId(35L)
                .clientId(clientId)
                .build();

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.findBypmsProjectId(35L))
                .thenReturn(Optional.of(existingProject));
        doThrow(new RuntimeException("Budget refresh failed"))
                .when(clientBudgetSummaryService).refreshClientBudget(clientId);

        assertDoesNotThrow(() -> projectDataProcessor.process(deletePayload));

        verify(projectMasterReferenceRepository).delete(existingProject);
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_DeleteOperation_ProjectNotFound() {
        CdcEventPayload deletePayload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("d")
                .before(afterData)
                .build();

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.findBypmsProjectId(35L))
                .thenReturn(Optional.empty());

        projectDataProcessor.process(deletePayload);

        verify(projectMasterReferenceRepository, never()).delete(any());
        verify(clientBudgetSummaryService, never()).refreshClientBudget(any());
    }

    @Test
    void testProcess_UnknownOperation() {
        CdcEventPayload payload = CdcEventPayload.builder()
                .connectorName("project-cdc")
                .entityType("PMS-PROJECTS")
                .entityId("35")
                .operation("x")
                .after(afterData)
                .build();

        assertThrows(IllegalArgumentException.class, () -> projectDataProcessor.process(payload));
    }

    @Test
    void testProcess_CreateOperation_MultiCurrencySupport() {
        afterData.put("project_budget_currency", "EUR");
        createPayload.setAfter(afterData);

        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.existsBypmsProjectId(35L)).thenReturn(false);
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(clientBudgetSummaryService).refreshClientBudget(clientId);

        projectDataProcessor.process(createPayload);

        verify(projectMasterReferenceRepository).save(argThat(project -> 
                "EUR".equals(project.getProjectBudgetCurrency())));
        verify(clientBudgetSummaryService).refreshClientBudget(clientId);
    }

    @Test
    void testProcess_Idempotency_RetrySameCreateEvent() {
        when(valueConverter.convertValue(35L, ProjectCdcMappingRegistry.PMS_TO_AR.get("id")))
                .thenReturn(35L);
        when(projectMasterReferenceRepository.existsBypmsProjectId(35L))
                .thenReturn(false)
                .thenReturn(true);
        when(projectMasterReferenceRepository.save(any(ProjectMasterReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(clientBudgetSummaryService).refreshClientBudget(clientId);

        projectDataProcessor.process(createPayload);

        projectDataProcessor.process(createPayload);

        verify(projectMasterReferenceRepository, times(1)).save(any(ProjectMasterReference.class));
        verify(clientBudgetSummaryService, times(1)).refreshClientBudget(clientId);
    }
}
