package com.payflowlab.reconciliation;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.reconciliation.ReconciliationRepositories.ExternalStatementLineRepository;
import com.payflowlab.reconciliation.ReconciliationRepositories.ExternalStatementRepository;
import com.payflowlab.reconciliation.ReconciliationRepositories.ReconciliationItemRepository;
import com.payflowlab.reconciliation.ReconciliationRepositories.ReconciliationRunRepository;
import com.payflowlab.settlement.SettlementBatch;
import com.payflowlab.settlement.SettlementRepositories.SettlementBatchRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    /** How a synthetic statement is produced from the platform's own completed batches. */
    public enum StatementScenario {
        /** Every completed batch appears exactly as settled: reconciliation should fully match. */
        CLEAN,
        /** Deterministic defects are injected so every exception type appears. */
        WITH_ANOMALIES
    }

    public record RunDetail(ReconciliationRun run, List<ReconciliationItem> items) {
    }

    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ExternalStatementRepository statements;
    private final ExternalStatementLineRepository statementLines;
    private final ReconciliationRunRepository runs;
    private final ReconciliationItemRepository items;
    private final SettlementBatchRepository batches;
    private final AuditService audit;
    private final AppClock clock;

    public ReconciliationService(ExternalStatementRepository statements,
                                 ExternalStatementLineRepository statementLines, ReconciliationRunRepository runs,
                                 ReconciliationItemRepository items, SettlementBatchRepository batches,
                                 AuditService audit, AppClock clock) {
        this.statements = statements;
        this.statementLines = statementLines;
        this.runs = runs;
        this.items = items;
        this.batches = batches;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------ statements

    /**
     * Builds a synthetic external statement for the period. In WITH_ANOMALIES
     * mode defects are chosen by position in the reference-sorted batch list,
     * never randomly, so the same data always yields the same exceptions:
     * <ul>
     *   <li>every 5th batch starting at the 2nd: paid 250 minor units short (AMOUNT_MISMATCH)</li>
     *   <li>every 5th batch starting at the 4th: missing from the statement (UNMATCHED_INTERNAL)</li>
     *   <li>every 7th batch starting at the 6th, if not already altered: value date 6 days late (DATE_MISMATCH)</li>
     *   <li>one extra line with a reference the platform never issued (UNMATCHED_EXTERNAL)</li>
     * </ul>
     */
    @Transactional
    public ExternalStatement generateStatement(LocalDate periodStart, LocalDate periodEnd,
                                               StatementScenario scenario) {
        if (periodStart.isAfter(periodEnd)) {
            throw ApiException.badRequest("INVALID_PERIOD", "periodStart must be on or before periodEnd");
        }
        List<SettlementBatch> completed = batches.findCompletedInPeriod(periodStart, periodEnd);
        if (completed.isEmpty()) {
            throw ApiException.unprocessable("NO_COMPLETED_BATCHES", "There are no completed settlement batches "
                    + "between " + periodStart + " and " + periodEnd + ". Run settlement first.");
        }

        Instant now = clock.now();
        long sequence = statements.countByPeriodStartAndPeriodEnd(periodStart, periodEnd) + 1;
        String reference = "STMT-" + REF_DATE.format(periodStart) + "-" + REF_DATE.format(periodEnd) + "-"
                + String.format("%02d", sequence);

        record Draft(String batchReference, String merchantCode, String currency, long amountMinor,
                     LocalDate valueDate) {
        }
        List<Draft> drafts = new ArrayList<>();
        boolean anomalies = scenario == StatementScenario.WITH_ANOMALIES;
        for (int i = 0; i < completed.size(); i++) {
            SettlementBatch batch = completed.get(i);
            long amount = batch.getNetMinor();
            LocalDate valueDate = batch.getSettlementDate().plusDays(1);
            if (anomalies && i % 5 == 3) {
                continue; // omitted from the statement
            }
            if (anomalies && i % 5 == 1) {
                amount = Math.max(1, amount - 250);
            } else if (anomalies && i % 7 == 5) {
                valueDate = batch.getSettlementDate().plusDays(6);
            }
            drafts.add(new Draft(batch.getBatchReference(), batch.getMerchant().getMerchantCode(),
                    batch.getCurrency(), amount, valueDate));
        }
        if (anomalies) {
            drafts.add(new Draft("STL-" + REF_DATE.format(periodEnd) + "-UNKNOWN-01", "UNKNOWN-MERCHANT",
                    completed.get(0).getCurrency(), 12_345, periodEnd.plusDays(1)));
        }

        ExternalStatement statement = statements.save(new ExternalStatement(reference,
                anomalies ? "SYNTHETIC_ANOMALIES" : "SYNTHETIC_CLEAN", periodStart, periodEnd, drafts.size(), now));
        statementLines.saveAll(drafts.stream().map(d -> new ExternalStatementLine(statement.getId(),
                d.batchReference(), d.merchantCode(), d.currency(), d.amountMinor(), d.valueDate())).toList());
        audit.record("EXTERNAL_STATEMENT", statement.getId(), "STATEMENT_GENERATED", AuditService.OPERATOR,
                Map.of("statementReference", reference, "scenario", scenario.name(), "lineCount", drafts.size()));
        return statement;
    }

    @Transactional(readOnly = true)
    public List<ExternalStatement> listStatements() {
        return statements.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<ExternalStatementLine> statementLines(UUID statementId) {
        if (!statements.existsById(statementId)) {
            throw ApiException.notFound("Statement", statementId);
        }
        return statementLines.findByStatementIdOrderByBatchReference(statementId);
    }

    // ------------------------------------------------------------------ runs

    /**
     * Reconciles one statement against the completed batches of its period.
     * Re-running is allowed and always creates a new run: history is never overwritten.
     */
    @Transactional
    public RunDetail run(UUID statementId) {
        ExternalStatement statement = statements.findById(statementId)
                .orElseThrow(() -> ApiException.notFound("Statement", statementId));

        List<ReconciliationMatcher.Expected> expected = batches
                .findCompletedInPeriod(statement.getPeriodStart(), statement.getPeriodEnd()).stream()
                .map(b -> new ReconciliationMatcher.Expected(b.getId(), b.getBatchReference(),
                        b.getMerchant().getMerchantCode(), b.getCurrency(), b.getNetMinor(), b.getSettlementDate()))
                .toList();
        List<ReconciliationMatcher.Actual> actual = statementLines
                .findByStatementIdOrderByBatchReference(statementId).stream()
                .map(l -> new ReconciliationMatcher.Actual(l.getId(), l.getBatchReference(), l.getMerchantCode(),
                        l.getCurrency(), l.getAmountMinor(), l.getValueDate()))
                .toList();

        List<ReconciliationMatcher.Result> results = ReconciliationMatcher.match(expected, actual);

        ReconciliationRun run = runs.saveAndFlush(new ReconciliationRun(statement, clock.now()));
        List<ReconciliationItem> saved = items.saveAll(
                results.stream().map(result -> new ReconciliationItem(run.getId(), result)).toList());
        run.complete(results, clock.now());

        audit.record("RECONCILIATION_RUN", run.getId(), "RECONCILIATION_RUN_EXECUTED", AuditService.OPERATOR,
                Map.of("statementReference", statement.getStatementReference(), "totalItems", results.size(),
                        "matched", run.getMatchedCount(), "exceptions", run.exceptionCount()));
        return new RunDetail(run, saved);
    }

    @Transactional(readOnly = true)
    public Page<ReconciliationRun> listRuns(Pageable pageable) {
        return runs.findAllWithStatement(pageable);
    }

    @Transactional(readOnly = true)
    public RunDetail getRun(UUID id) {
        ReconciliationRun run = runs.findDetailed(id)
                .orElseThrow(() -> ApiException.notFound("Reconciliation run", id));
        return new RunDetail(run, items.findByRunIdOrderByOutcomeAscBatchReferenceAsc(id));
    }

    // ------------------------------------------------------------ exceptions

    @Transactional(readOnly = true)
    public Page<ReconciliationItem> exceptions(ReconciliationItem.ReviewStatus status, UUID runId,
                                               Pageable pageable) {
        if (status == ReconciliationItem.ReviewStatus.NOT_REQUIRED) {
            throw ApiException.badRequest("INVALID_REVIEW_STATUS", "reviewStatus must be OPEN or REVIEWED");
        }
        return items.findExceptions(status, runId, pageable);
    }

    @Transactional
    public ReconciliationItem review(UUID itemId, String note) {
        ReconciliationItem item = items.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("Reconciliation item", itemId));
        item.review(note.trim(), clock.now());
        audit.record("RECONCILIATION_ITEM", item.getId(), "EXCEPTION_REVIEWED", AuditService.OPERATOR,
                Map.of("batchReference", item.getBatchReference(), "outcome", item.getOutcome().name()));
        return item;
    }
}
