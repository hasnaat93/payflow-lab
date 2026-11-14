package com.payflowlab.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class ReconciliationRepositories {

    private ReconciliationRepositories() {
    }

    public interface ExternalStatementRepository extends JpaRepository<ExternalStatement, UUID> {

        long countByPeriodStartAndPeriodEnd(LocalDate periodStart, LocalDate periodEnd);

        List<ExternalStatement> findAllByOrderByCreatedAtDesc();
    }

    public interface ExternalStatementLineRepository extends JpaRepository<ExternalStatementLine, UUID> {

        List<ExternalStatementLine> findByStatementIdOrderByBatchReference(UUID statementId);
    }

    public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

        @EntityGraph(attributePaths = "statement")
        @Query("select r from ReconciliationRun r")
        Page<ReconciliationRun> findAllWithStatement(Pageable pageable);

        @EntityGraph(attributePaths = "statement")
        @Query("select r from ReconciliationRun r where r.id = :id")
        Optional<ReconciliationRun> findDetailed(@Param("id") UUID id);
    }

    public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {

        List<ReconciliationItem> findByRunIdOrderByOutcomeAscBatchReferenceAsc(UUID runId);

        @Query("""
                select i from ReconciliationItem i
                where i.reviewStatus <> com.payflowlab.reconciliation.ReconciliationItem.ReviewStatus.NOT_REQUIRED
                  and (:status is null or i.reviewStatus = :status)
                  and (:runId is null or i.runId = :runId)
                """)
        Page<ReconciliationItem> findExceptions(@Param("status") ReconciliationItem.ReviewStatus status,
                                                @Param("runId") UUID runId, Pageable pageable);
    }
}
