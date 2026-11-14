package com.payflowlab.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A capture (positive) or a refund (negative net) included in a batch. */
@Entity
@Table(name = "settlement_batch_lines")
public class SettlementBatchLine {

    public static final String CAPTURE = "CAPTURE";
    public static final String REFUND = "REFUND";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID batchId;

    @Column(nullable = false, updatable = false)
    private String lineType;

    @Column(nullable = false, updatable = false)
    private UUID transactionId;

    @Column(updatable = false)
    private UUID refundId;

    @Column(nullable = false, updatable = false)
    private long grossMinor;

    @Column(nullable = false, updatable = false)
    private long feeMinor;

    @Column(nullable = false, updatable = false)
    private long netMinor;

    protected SettlementBatchLine() {
    }

    private SettlementBatchLine(UUID batchId, String lineType, UUID transactionId, UUID refundId, long grossMinor,
                                long feeMinor, long netMinor) {
        this.batchId = batchId;
        this.lineType = lineType;
        this.transactionId = transactionId;
        this.refundId = refundId;
        this.grossMinor = grossMinor;
        this.feeMinor = feeMinor;
        this.netMinor = netMinor;
    }

    public static SettlementBatchLine capture(UUID batchId, UUID transactionId, long grossMinor, long feeMinor) {
        return new SettlementBatchLine(batchId, CAPTURE, transactionId, null, grossMinor, feeMinor,
                grossMinor - feeMinor);
    }

    public static SettlementBatchLine refund(UUID batchId, UUID transactionId, UUID refundId, long refundMinor) {
        return new SettlementBatchLine(batchId, REFUND, transactionId, refundId, refundMinor, 0, -refundMinor);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public String getLineType() {
        return lineType;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public long getGrossMinor() {
        return grossMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getNetMinor() {
        return netMinor;
    }
}
