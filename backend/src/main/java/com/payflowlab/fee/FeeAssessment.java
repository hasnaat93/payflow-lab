package com.payflowlab.fee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable snapshot of the fee charged on one captured transaction, including
 * the plan terms as they were at that moment. Later edits to the fee plan do
 * not touch this row, so historical fees are stable and explainable.
 */
@Entity
@Table(name = "fee_assessments")
public class FeeAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, updatable = false)
    private UUID feePlanId;

    @Column(nullable = false, updatable = false)
    private String planCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private long grossMinor;

    @Column(nullable = false, updatable = false)
    private int percentageBps;

    @Column(nullable = false, updatable = false)
    private long fixedFeeMinor;

    @Column(updatable = false)
    private Long minFeeMinor;

    @Column(updatable = false)
    private Long maxFeeMinor;

    @Column(nullable = false, updatable = false)
    private long percentageComponentMinor;

    @Column(nullable = false, updatable = false)
    private long fixedComponentMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FeeCalculator.Adjustment adjustment;

    @Column(nullable = false, updatable = false)
    private long totalFeeMinor;

    @Column(nullable = false, updatable = false)
    private long netMinor;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected FeeAssessment() {
    }

    public FeeAssessment(UUID transactionId, FeePlan plan, FeeCalculator.FeeBreakdown breakdown, Instant now) {
        this.transactionId = transactionId;
        this.feePlanId = plan.getId();
        this.planCode = plan.getCode();
        this.currency = plan.getCurrency();
        this.grossMinor = breakdown.grossMinor();
        this.percentageBps = plan.getPercentageBps();
        this.fixedFeeMinor = plan.getFixedFeeMinor();
        this.minFeeMinor = plan.getMinFeeMinor();
        this.maxFeeMinor = plan.getMaxFeeMinor();
        this.percentageComponentMinor = breakdown.percentageComponentMinor();
        this.fixedComponentMinor = breakdown.fixedComponentMinor();
        this.adjustment = breakdown.adjustment();
        this.totalFeeMinor = breakdown.totalFeeMinor();
        this.netMinor = breakdown.netMinor();
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getFeePlanId() {
        return feePlanId;
    }

    public String getPlanCode() {
        return planCode;
    }

    public String getCurrency() {
        return currency;
    }

    public long getGrossMinor() {
        return grossMinor;
    }

    public int getPercentageBps() {
        return percentageBps;
    }

    public long getFixedFeeMinor() {
        return fixedFeeMinor;
    }

    public Long getMinFeeMinor() {
        return minFeeMinor;
    }

    public Long getMaxFeeMinor() {
        return maxFeeMinor;
    }

    public long getPercentageComponentMinor() {
        return percentageComponentMinor;
    }

    public long getFixedComponentMinor() {
        return fixedComponentMinor;
    }

    public FeeCalculator.Adjustment getAdjustment() {
        return adjustment;
    }

    public long getTotalFeeMinor() {
        return totalFeeMinor;
    }

    public long getNetMinor() {
        return netMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
