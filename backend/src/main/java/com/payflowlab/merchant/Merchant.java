package com.payflowlab.merchant;

import com.payflowlab.fee.FeePlan;
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

@Entity
@Table(name = "merchants")
public class Merchant {

    public enum Status {
        ACTIVE, SUSPENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String merchantCode;

    @Column(nullable = false)
    private String displayName;

    private String legalName;

    /** Immutable: a merchant's history, balances and settlements are all in this one currency. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_plan_id")
    private FeePlan feePlan;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Merchant() {
    }

    public Merchant(String merchantCode, String displayName, String legalName, String currency, FeePlan feePlan,
                    Instant now) {
        this.merchantCode = merchantCode;
        this.displayName = displayName;
        this.legalName = legalName;
        this.currency = currency;
        this.feePlan = feePlan;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String displayName, String legalName, FeePlan feePlan, Instant now) {
        this.displayName = displayName;
        this.legalName = legalName;
        this.feePlan = feePlan;
        this.updatedAt = now;
    }

    public void setStatus(Status status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantCode() {
        return merchantCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public FeePlan getFeePlan() {
        return feePlan;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
