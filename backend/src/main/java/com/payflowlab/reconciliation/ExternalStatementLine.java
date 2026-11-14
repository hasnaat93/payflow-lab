package com.payflowlab.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "external_statement_lines")
public class ExternalStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID statementId;

    @Column(nullable = false, updatable = false)
    private String batchReference;

    @Column(nullable = false, updatable = false)
    private String merchantCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private LocalDate valueDate;

    protected ExternalStatementLine() {
    }

    public ExternalStatementLine(UUID statementId, String batchReference, String merchantCode, String currency,
                                 long amountMinor, LocalDate valueDate) {
        this.statementId = statementId;
        this.batchReference = batchReference;
        this.merchantCode = merchantCode;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.valueDate = valueDate;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStatementId() {
        return statementId;
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

    public long getAmountMinor() {
        return amountMinor;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }
}
