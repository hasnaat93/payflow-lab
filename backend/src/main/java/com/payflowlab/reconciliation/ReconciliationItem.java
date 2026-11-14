package com.payflowlab.reconciliation;

import com.payflowlab.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The outcome for one batch reference in one run. Anything not MATCHED is an exception to review. */
@Entity
@Table(name = "reconciliation_items")
public class ReconciliationItem {

    public enum ReviewStatus {
        NOT_REQUIRED, OPEN, REVIEWED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReconciliationMatcher.Outcome outcome;

    @Column(nullable = false, updatable = false)
    private String batchReference;

    @Column(updatable = false)
    private String merchantCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(updatable = false, length = 3)
    private String currency;

    @Column(updatable = false)
    private UUID batchId;

    @Column(updatable = false)
    private UUID statementLineId;

    @Column(updatable = false)
    private Long expectedMinor;

    @Column(updatable = false)
    private Long actualMinor;

    @Column(updatable = false)
    private Long differenceMinor;

    @Column(nullable = false, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus reviewStatus;

    private String resolutionNote;

    private Instant reviewedAt;

    @Version
    private long version;

    protected ReconciliationItem() {
    }

    public ReconciliationItem(UUID runId, ReconciliationMatcher.Result result) {
        this.runId = runId;
        this.outcome = result.outcome();
        this.batchReference = result.reference();
        this.merchantCode = result.merchantCode();
        this.currency = result.currency();
        this.batchId = result.batchId();
        this.statementLineId = result.lineId();
        this.expectedMinor = result.expectedMinor();
        this.actualMinor = result.actualMinor();
        this.differenceMinor = result.differenceMinor();
        this.detail = result.detail();
        this.reviewStatus = result.outcome() == ReconciliationMatcher.Outcome.MATCHED
                ? ReviewStatus.NOT_REQUIRED
                : ReviewStatus.OPEN;
    }

    /**
     * Reviewing records that an operator looked at the exception and why it is
     * acceptable or how it was resolved. It never changes the outcome: a
     * mismatch stays a mismatch.
     */
    public void review(String note, Instant now) {
        if (reviewStatus != ReviewStatus.OPEN) {
            throw ApiException.conflict("EXCEPTION_NOT_OPEN", reviewStatus == ReviewStatus.REVIEWED
                    ? "This exception has already been reviewed"
                    : "Matched items do not need review");
        }
        this.reviewStatus = ReviewStatus.REVIEWED;
        this.resolutionNote = note;
        this.reviewedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public ReconciliationMatcher.Outcome getOutcome() {
        return outcome;
    }

    public String getBatchReference() {
        return batchReference;
    }

    public String getMerchantCode() {
        return merchantCode;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getStatementLineId() {
        return statementLineId;
    }

    public Long getExpectedMinor() {
        return expectedMinor;
    }

    public Long getActualMinor() {
        return actualMinor;
    }

    public Long getDifferenceMinor() {
        return differenceMinor;
    }

    public String getDetail() {
        return detail;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
