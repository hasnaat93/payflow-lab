package com.payflowlab.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A synthetic "bank/network statement" of payouts, used as the external side of reconciliation. */
@Entity
@Table(name = "external_statements")
public class ExternalStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String statementReference;

    @Column(nullable = false, updatable = false)
    private String source;

    @Column(nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(nullable = false)
    private int lineCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ExternalStatement() {
    }

    public ExternalStatement(String statementReference, String source, LocalDate periodStart, LocalDate periodEnd,
                             int lineCount, Instant createdAt) {
        this.statementReference = statementReference;
        this.source = source;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.lineCount = lineCount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getStatementReference() {
        return statementReference;
    }

    public String getSource() {
        return source;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public int getLineCount() {
        return lineCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
