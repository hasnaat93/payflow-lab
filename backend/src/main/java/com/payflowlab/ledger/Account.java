package com.payflowlab.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Chart-of-accounts entry. Reference data owned by the Flyway migration. */
@Entity
@Immutable
@Table(name = "accounts")
public class Account {

    /** The simulator's chart of accounts. Codes match the rows inserted by V2__ledger.sql. */
    public static final String NETWORK_RECEIVABLE = "1000";
    public static final String MERCHANT_PAYABLE = "2000";
    public static final String SETTLEMENT_CLEARING = "2100";
    public static final String REFUND_CLEARING = "2200";
    public static final String FEE_REVENUE = "4000";

    public enum Side {
        DEBIT, CREDIT
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side normalSide;

    @Column(nullable = false)
    private String description;

    protected Account() {
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

    public String getType() {
        return type;
    }

    public Side getNormalSide() {
        return normalSide;
    }

    public String getDescription() {
        return description;
    }
}
