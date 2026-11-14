package com.payflowlab.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One execution of the matcher against one statement. Runs are kept forever as history. */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", updatable = false)
    private ExternalStatement statement;

    @Column(nullable = false)
    private String status = "COMPLETED";

    @Column(nullable = false)
    private int totalItems;

    @Column(nullable = false)
    private int matchedCount;

    @Column(nullable = false)
    private int amountMismatchCount;

    @Column(nullable = false)
    private int dateMismatchCount;

    @Column(nullable = false)
    private int unmatchedInternalCount;

    @Column(nullable = false)
    private int unmatchedExternalCount;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private Instant completedAt;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(ExternalStatement statement, Instant startedAt) {
        this.statement = statement;
        this.startedAt = startedAt;
    }

    public void complete(List<ReconciliationMatcher.Result> results, Instant now) {
        this.totalItems = results.size();
        this.matchedCount = count(results, ReconciliationMatcher.Outcome.MATCHED);
        this.amountMismatchCount = count(results, ReconciliationMatcher.Outcome.AMOUNT_MISMATCH);
        this.dateMismatchCount = count(results, ReconciliationMatcher.Outcome.DATE_MISMATCH);
        this.unmatchedInternalCount = count(results, ReconciliationMatcher.Outcome.UNMATCHED_INTERNAL);
        this.unmatchedExternalCount = count(results, ReconciliationMatcher.Outcome.UNMATCHED_EXTERNAL);
        this.completedAt = now;
    }

    private static int count(List<ReconciliationMatcher.Result> results, ReconciliationMatcher.Outcome outcome) {
        return (int) results.stream().filter(r -> r.outcome() == outcome).count();
    }

    public int exceptionCount() {
        return totalItems - matchedCount;
    }

    public UUID getId() {
        return id;
    }

    public ExternalStatement getStatement() {
        return statement;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public int getAmountMismatchCount() {
        return amountMismatchCount;
    }

    public int getDateMismatchCount() {
        return dateMismatchCount;
    }

    public int getUnmatchedInternalCount() {
        return unmatchedInternalCount;
    }

    public int getUnmatchedExternalCount() {
        return unmatchedExternalCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
