package com.payflowlab.settlement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class SettlementRepositories {

    private SettlementRepositories() {
    }

    public interface SettlementBatchRepository
            extends JpaRepository<SettlementBatch, UUID>, JpaSpecificationExecutor<SettlementBatch> {

        @Query("select count(b) from SettlementBatch b where b.merchant.id = :merchantId and b.settlementDate = :date")
        long countForMerchantAndDate(@Param("merchantId") UUID merchantId, @Param("date") LocalDate date);

        @EntityGraph(attributePaths = "merchant")
        @Query("select b from SettlementBatch b where b.id = :id")
        Optional<SettlementBatch> findDetailed(@Param("id") UUID id);

        @Override
        @EntityGraph(attributePaths = "merchant")
        Page<SettlementBatch> findAll(Specification<SettlementBatch> spec, Pageable pageable);

        @EntityGraph(attributePaths = "merchant")
        @Query("""
                select b from SettlementBatch b
                where b.status = com.payflowlab.settlement.SettlementBatch.Status.COMPLETED
                  and b.settlementDate between :start and :end
                order by b.batchReference
                """)
        List<SettlementBatch> findCompletedInPeriod(@Param("start") LocalDate start, @Param("end") LocalDate end);
    }

    public interface SettlementBatchLineRepository extends JpaRepository<SettlementBatchLine, UUID> {
    }
}
