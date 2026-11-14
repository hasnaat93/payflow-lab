package com.payflowlab.transaction;

import com.payflowlab.common.ApiException;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A card payment. State only changes through the methods below, each of which
 * checks the state machine first, so an invalid transition cannot be persisted.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String externalReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", updatable = false)
    private Merchant merchant;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false, updatable = false)
    private String cardBrand;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 4)
    private String cardLast4;

    @Column(nullable = false, updatable = false)
    private String cardToken;

    @Column(updatable = false)
    private String declineReason;

    @Column(nullable = false)
    private long capturedAmountMinor;

    @Column(nullable = false)
    private long refundedAmountMinor;

    @Column(nullable = false)
    private long feeMinor;

    private Instant capturedAt;

    private UUID settlementBatchId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Transaction() {
    }

    private Transaction(String externalReference, Merchant merchant, long amountMinor, String cardBrand,
                        String cardLast4, String cardToken, TransactionStatus status, String declineReason,
                        Instant now) {
        this.externalReference = externalReference;
        this.merchant = merchant;
        this.amountMinor = amountMinor;
        this.currency = merchant.getCurrency();
        this.cardBrand = cardBrand;
        this.cardLast4 = cardLast4;
        this.cardToken = cardToken;
        this.status = status;
        this.declineReason = declineReason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Transaction authorized(String externalReference, Merchant merchant, long amountMinor,
                                         String cardBrand, String cardLast4, String cardToken, Instant now) {
        return new Transaction(externalReference, merchant, amountMinor, cardBrand, cardLast4, cardToken,
                TransactionStatus.AUTHORIZED, null, now);
    }

    public static Transaction declined(String externalReference, Merchant merchant, long amountMinor,
                                       String cardBrand, String cardLast4, String cardToken, String declineReason,
                                       Instant now) {
        return new Transaction(externalReference, merchant, amountMinor, cardBrand, cardLast4, cardToken,
                TransactionStatus.DECLINED, declineReason, now);
    }

    public void capture(long feeMinor, Instant now) {
        moveTo(TransactionStatus.CAPTURED, "capture", now);
        this.capturedAmountMinor = amountMinor;
        this.feeMinor = feeMinor;
        this.capturedAt = now;
    }

    public void reverse(Instant now) {
        moveTo(TransactionStatus.REVERSED, "reverse", now);
    }

    public void refund(long refundMinor, Instant now) {
        long refundable = refundableMinor();
        if (status != TransactionStatus.CAPTURED && status != TransactionStatus.PARTIALLY_REFUNDED) {
            throw invalidTransition("refund");
        }
        if (refundMinor > refundable) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_CAPTURED", "Refund of " + refundMinor
                    + " exceeds the remaining refundable amount of " + refundable + " (minor units)");
        }
        TransactionStatus target = refundMinor == refundable
                ? TransactionStatus.REFUNDED
                : TransactionStatus.PARTIALLY_REFUNDED;
        moveTo(target, "refund", now);
        this.refundedAmountMinor += refundMinor;
    }

    public void markSettled(UUID batchId, Instant now) {
        if (settlementBatchId != null) {
            throw new IllegalStateException("Transaction " + id + " is already settled in " + settlementBatchId);
        }
        this.settlementBatchId = batchId;
        this.updatedAt = now;
    }

    private void moveTo(TransactionStatus target, String operation, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw invalidTransition(operation);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private ApiException invalidTransition(String operation) {
        return ApiException.conflict("INVALID_STATE_TRANSITION",
                "Cannot " + operation + " a transaction in status " + status);
    }

    public long refundableMinor() {
        return capturedAmountMinor - refundedAmountMinor;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public String getCardToken() {
        return cardToken;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public long getCapturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public UUID getSettlementBatchId() {
        return settlementBatchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
