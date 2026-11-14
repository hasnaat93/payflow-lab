package com.payflowlab.settlement;

import com.payflowlab.merchant.Merchant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One payout to one merchant, in one currency, for one settlement date. */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch {

    public enum Status {
        CREATED, PROCESSING, COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String batchReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", updatable = false)
    private Merchant merchant;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CREATED;

    @Column(nullable = false)
    private int transactionCount;

    @Column(nullable = false)
    private int refundCount;

    @Column(nullable = false)
    private long grossMinor;

    @Column(nullable = false)
    private long feeMinor;

    @Column(nullable = false)
    private long refundMinor;

    @Column(nullable = false)
    private long netMinor;

    private String summary;

    private String failureReason;

    private Instant startedAt;

    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementBatch() {
    }

    public SettlementBatch(String batchReference, Merchant merchant, LocalDate settlementDate, Instant now) {
        this.batchReference = batchReference;
        this.merchant = merchant;
        this.currency = merchant.getCurrency();
        this.settlementDate = settlementDate;
        this.createdAt = now;
    }

    public void start(Instant now) {
        this.status = Status.PROCESSING;
        this.startedAt = now;
    }

    public void totals(int transactionCount, int refundCount, long grossMinor, long feeMinor, long refundMinor) {
        this.transactionCount = transactionCount;
        this.refundCount = refundCount;
        this.grossMinor = grossMinor;
        this.feeMinor = feeMinor;
        this.refundMinor = refundMinor;
        this.netMinor = grossMinor - feeMinor - refundMinor;
    }

    public void complete(String summary, Instant now) {
        this.status = Status.COMPLETED;
        this.summary = summary;
        this.completedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.summary = "Nothing was paid out; eligible items remain unsettled and carry forward to the next run.";
        this.completedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getBatchReference() {
        return batchReference;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public Status getStatus() {
        return status;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public int getRefundCount() {
        return refundCount;
    }

    public long getGrossMinor() {
        return grossMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getRefundMinor() {
        return refundMinor;
    }

    public long getNetMinor() {
        return netMinor;
    }

    public String getSummary() {
        return summary;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
