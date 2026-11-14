package com.payflowlab.settlement;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.ledger.JournalEntry;
import com.payflowlab.ledger.LedgerService;
import com.payflowlab.merchant.MerchantRepository;
import com.payflowlab.settlement.SettlementRepositories.SettlementBatchRepository;
import com.payflowlab.transaction.TransactionRepositories.RefundRepository;
import com.payflowlab.transaction.TransactionRepositories.TransactionRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a settlement run. The run itself is not one big transaction:
 * each merchant is settled independently by {@link MerchantSettlementProcessor},
 * so one merchant's failure never blocks or rolls back another's payout.
 *
 * <p>The same {@link #run} is called by the operator endpoint and by the
 * optional scheduler; scheduling is purely a trigger, not business logic.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    public record RunResult(LocalDate settlementDate, int merchantsConsidered, int completed, int failed,
                            long transactionsSettled, List<SettlementBatch> batches, String summary) {
    }

    public record BatchLineView(UUID id, String lineType, UUID transactionId, String externalReference,
                                String refundReference, long grossMinor, long feeMinor, long netMinor) {
    }

    public record BatchDetail(SettlementBatch batch, List<BatchLineView> lines, List<JournalEntry> journalEntries) {
    }

    private final MerchantSettlementProcessor processor;
    private final SettlementBatchRepository batches;
    private final TransactionRepository transactions;
    private final RefundRepository refunds;
    private final MerchantRepository merchants;
    private final LedgerService ledger;
    private final AuditService audit;
    private final AppClock clock;
    private final JdbcTemplate jdbc;

    public SettlementService(MerchantSettlementProcessor processor, SettlementBatchRepository batches,
                             TransactionRepository transactions, RefundRepository refunds,
                             MerchantRepository merchants, LedgerService ledger, AuditService audit,
                             AppClock clock, JdbcTemplate jdbc) {
        this.processor = processor;
        this.batches = batches;
        this.transactions = transactions;
        this.refunds = refunds;
        this.merchants = merchants;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /**
     * Settles everything captured or refunded up to the end of {@code settlementDate} (UTC).
     *
     * @param settlementDate defaults to today (UTC)
     * @param merchantId     optional: restrict the run to one merchant
     */
    public RunResult run(LocalDate settlementDate, UUID merchantId) {
        LocalDate today = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        LocalDate date = settlementDate != null ? settlementDate : today;
        if (date.isAfter(today)) {
            throw ApiException.badRequest("SETTLEMENT_DATE_IN_FUTURE", "Settlement date cannot be after " + today);
        }
        Instant cutoff = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        TreeSet<UUID> candidates = new TreeSet<>();
        if (merchantId != null) {
            if (!merchants.existsById(merchantId)) {
                throw ApiException.notFound("Merchant", merchantId);
            }
            candidates.add(merchantId);
        } else {
            candidates.addAll(transactions.findMerchantsWithUnsettledCaptures(cutoff));
            candidates.addAll(refunds.findMerchantsWithUnsettledRefunds(cutoff));
        }

        List<SettlementBatch> created = new ArrayList<>();
        for (UUID candidate : candidates) {
            try {
                processor.settle(candidate, date, cutoff).ifPresent(created::add);
            } catch (RuntimeException e) {
                // the merchant's transaction has rolled back; record the failure and carry on
                log.error("Settlement failed for merchant {} on {}", candidate, date, e);
                created.add(processor.recordFailure(candidate, date, e.getClass().getSimpleName()));
            }
        }

        int completed = (int) created.stream().filter(b -> b.getStatus() == SettlementBatch.Status.COMPLETED).count();
        int failed = created.size() - completed;
        long settled = created.stream().filter(b -> b.getStatus() == SettlementBatch.Status.COMPLETED)
                .mapToLong(SettlementBatch::getTransactionCount).sum();
        String summary = created.isEmpty()
                ? "Nothing to settle for " + date + ": no captured transactions or refunds are waiting."
                : "Settlement for " + date + ": " + completed + " batch(es) completed, " + failed + " failed, "
                        + settled + " transaction(s) settled.";
        audit.record("SETTLEMENT_RUN", date, "SETTLEMENT_RUN_EXECUTED", AuditService.OPERATOR,
                Map.of("settlementDate", date.toString(), "completed", completed, "failed", failed,
                        "transactionsSettled", settled));
        // reload with merchants so callers can render the result outside a transaction
        List<SettlementBatch> detailed = created.stream()
                .map(batch -> batches.findDetailed(batch.getId()).orElseThrow()).toList();
        return new RunResult(date, candidates.size(), completed, failed, settled, detailed, summary);
    }

    @Transactional(readOnly = true)
    public Page<SettlementBatch> search(UUID merchantId, SettlementBatch.Status status, LocalDate from,
                                        LocalDate to, Pageable pageable) {
        Specification<SettlementBatch> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (merchantId != null) {
                where.add(cb.equal(root.get("merchant").get("id"), merchantId));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("settlementDate"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("settlementDate"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return batches.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public BatchDetail detail(UUID id) {
        SettlementBatch batch = batches.findDetailed(id)
                .orElseThrow(() -> ApiException.notFound("Settlement batch", id));
        List<BatchLineView> lines = jdbc.query("""
                SELECT l.id, l.line_type, l.transaction_id, t.external_reference, r.refund_reference,
                       l.gross_minor, l.fee_minor, l.net_minor
                  FROM settlement_batch_lines l
                  JOIN transactions t ON t.id = l.transaction_id
                  LEFT JOIN refunds r ON r.id = l.refund_id
                 WHERE l.batch_id = ?
                 ORDER BY l.line_type, t.external_reference
                """, (rs, i) -> new BatchLineView(rs.getObject("id", UUID.class), rs.getString("line_type"),
                rs.getObject("transaction_id", UUID.class), rs.getString("external_reference"),
                rs.getString("refund_reference"), rs.getLong("gross_minor"), rs.getLong("fee_minor"),
                rs.getLong("net_minor")), id);
        return new BatchDetail(batch, lines, ledger.forSource(MerchantSettlementProcessor.SOURCE_BATCH, id));
    }
}
