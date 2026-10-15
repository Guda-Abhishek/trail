package com.AccountReceivableManagement.repo.project;

import com.AccountReceivableManagement.entity.project_entity.ProjectMasterReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMasterReferenceRepository extends JpaRepository<ProjectMasterReference, Long> {

    boolean existsBypmsProjectId(Long pmsProjectId);

    Optional<ProjectMasterReference> findBypmsProjectId(Long pmsProjectId);

    /** How many of a client's projects share a project code (not unique in the schema). */
    long countByClientIdAndProjectCodeIgnoreCase(UUID clientId, String projectCode);

    List<ProjectMasterReference> findByClientIdOrderByProjectNameAsc(UUID clientId);

    @Query("""
SELECT COALESCE(SUM(p.projectBudget),0)
FROM ProjectMasterReference p
WHERE p.clientId=:clientId
""")
    BigDecimal calculateTotalBudget(
            @Param("clientId")
            UUID clientId);

    @Query("""
SELECT DISTINCT p.projectBudgetCurrency
FROM ProjectMasterReference p
WHERE p.clientId=:clientId
""")
    List<String> getCurrencies(
            @Param("clientId")
            UUID clientId);

    @Query("""
SELECT p.projectBudgetCurrency, COALESCE(SUM(p.projectBudget),0), COUNT(p)
FROM ProjectMasterReference p
WHERE p.clientId=:clientId
GROUP BY p.projectBudgetCurrency
""")
    List<Object[]> getBudgetSummaryByCurrency(
            @Param("clientId")
            UUID clientId);

    long countByClientId(UUID clientId);

    long countByClientIdAndProjectBudgetCurrency(UUID clientId, String currency);
}
