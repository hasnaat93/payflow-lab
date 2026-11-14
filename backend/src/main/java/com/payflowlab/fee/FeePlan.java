package com.payflowlab.fee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fee_plans")
public class FeePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    /** Fixed, minimum and maximum fees are amounts, so a plan is tied to one currency. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private int percentageBps;

    @Column(nullable = false)
    private long fixedFeeMinor;

    private Long minFeeMinor;

    private Long maxFeeMinor;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FeePlan() {
    }

    public FeePlan(String code, String name, String currency, int percentageBps, long fixedFeeMinor,
                   Long minFeeMinor, Long maxFeeMinor, Instant now) {
        this.code = code;
        this.name = name;
        this.currency = currency;
        this.createdAt = now;
        applyTerms(name, percentageBps, fixedFeeMinor, minFeeMinor, maxFeeMinor, now);
    }

    public void applyTerms(String name, int percentageBps, long fixedFeeMinor, Long minFeeMinor, Long maxFeeMinor,
                           Instant now) {
        this.name = name;
        this.percentageBps = percentageBps;
        this.fixedFeeMinor = fixedFeeMinor;
        this.minFeeMinor = minFeeMinor;
        this.maxFeeMinor = maxFeeMinor;
        this.updatedAt = now;
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    public FeeCalculator.FeeTerms terms() {
        return new FeeCalculator.FeeTerms(percentageBps, fixedFeeMinor, minFeeMinor, maxFeeMinor);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
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

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
