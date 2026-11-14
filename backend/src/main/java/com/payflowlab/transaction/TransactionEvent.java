package com.payflowlab.transaction;

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

/** One row per state-changing operation on a transaction: its full, ordered history. */
@Entity
@Table(name = "transaction_events")
public class TransactionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private TransactionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionStatus toStatus;

    @Column(updatable = false)
    private Long amountMinor;

    @Column(updatable = false)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionEvent() {
    }

    public TransactionEvent(UUID transactionId, String eventType, TransactionStatus fromStatus,
                            TransactionStatus toStatus, Long amountMinor, String detail, Instant createdAt) {
        this.transactionId = transactionId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.amountMinor = amountMinor;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getEventType() {
        return eventType;
    }

    public TransactionStatus getFromStatus() {
        return fromStatus;
    }

    public TransactionStatus getToStatus() {
        return toStatus;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
