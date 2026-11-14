package com.payflowlab.ledger;

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
import java.util.UUID;

@Entity
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", updatable = false)
    private JournalEntry entry;

    @Column(nullable = false, updatable = false)
    private short lineNo;

    // five-row reference table; eager loading here is one cached lookup per account
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id", updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Account.Side side;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    protected JournalLine() {
    }

    JournalLine(JournalEntry entry, short lineNo, Account account, Account.Side side, long amountMinor) {
        this.entry = entry;
        this.lineNo = lineNo;
        this.account = account;
        this.side = side;
        this.amountMinor = amountMinor;
    }

    public UUID getId() {
        return id;
    }

    public short getLineNo() {
        return lineNo;
    }

    public Account getAccount() {
        return account;
    }

    public Account.Side getSide() {
        return side;
    }

    public long getAmountMinor() {
        return amountMinor;
    }
}
