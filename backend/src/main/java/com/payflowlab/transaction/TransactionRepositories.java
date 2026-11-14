package com.payflowlab.transaction;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class TransactionRepositories {

    private TransactionRepositories() {
    }

    public interface TransactionRepository
            extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

        boolean existsByExternalReference(String externalReference);

        @EntityGraph(attributePaths = {"merchant", "merchant.feePlan"})
        @Query("select t from Transaction t where t.id = :id")
        Optional<Transaction> findDetailed(@Param("id") UUID id);

        /**
         * Row lock for every money-moving operation. Two concurrent refunds (or a
         * capture racing a reversal) queue here, and the second one sees the
         * first one's committed state before it validates anything.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @EntityGraph(attributePaths = {"merchant", "merchant.feePlan"})
        @Query("select t from Transaction t where t.id = :id")
        Optional<Transaction> findForUpdate(@Param("id") UUID id);

        @Override
        @EntityGraph(attributePaths = "merchant")
        Page<Transaction> findAll(Specification<Transaction> spec, Pageable pageable);

        /** Captured, not yet settled, captured before the settlement cut-off. Locked for the batch. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("""
                select t from Transaction t
                where t.merchant.id = :merchantId
                  and t.settlementBatchId is null
                  and t.capturedAt is not null
                  and t.capturedAt < :cutoff
                order by t.capturedAt, t.id
                """)
        List<Transaction> findEligibleForSettlement(@Param("merchantId") UUID merchantId,
                                                    @Param("cutoff") Instant cutoff);

        @Query("""
                select distinct t.merchant.id from Transaction t
                where t.settlementBatchId is null and t.capturedAt is not null and t.capturedAt < :cutoff
                """)
        List<UUID> findMerchantsWithUnsettledCaptures(@Param("cutoff") Instant cutoff);
    }

    public interface TransactionEventRepository extends JpaRepository<TransactionEvent, UUID> {

        List<TransactionEvent> findByTransactionIdOrderByCreatedAtAscIdAsc(UUID transactionId);
    }

    public interface RefundRepository extends JpaRepository<Refund, UUID> {

        List<Refund> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

        /** Refunds are never edited after creation, so the batch reads them without a row lock. */
        @Query("""
                select r from Refund r
                where r.settlementBatchId is null
                  and r.createdAt < :cutoff
                  and r.transactionId in (select t.id from Transaction t where t.merchant.id = :merchantId)
                order by r.createdAt, r.id
                """)
        List<Refund> findEligibleForSettlement(@Param("merchantId") UUID merchantId,
                                               @Param("cutoff") Instant cutoff);

        @Query("""
                select distinct t.merchant.id from Refund r, Transaction t
                where t.id = r.transactionId and r.settlementBatchId is null and r.createdAt < :cutoff
                """)
        List<UUID> findMerchantsWithUnsettledRefunds(@Param("cutoff") Instant cutoff);
    }

    public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

        Optional<IdempotencyRecord> findByIdempotencyKeyAndOperation(String idempotencyKey, String operation);
    }
}
