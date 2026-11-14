package com.payflowlab.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, updatable = false)
    private String refundReference;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false)
    private String reason;

    private UUID settlementBatchId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Refund() {
    }

    public Refund(UUID transactionId, String refundReference, long amountMinor, String currency, String reason,
                  Instant createdAt) {
        this.transactionId = transactionId;
        this.refundReference = refundReference;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public void markSettled(UUID batchId) {
        if (settlementBatchId != null) {
            throw new IllegalStateException("Refund " + id + " is already settled in " + settlementBatchId);
        }
        this.settlementBatchId = batchId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReason() {
        return reason;
    }

    public UUID getSettlementBatchId() {
        return settlementBatchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
