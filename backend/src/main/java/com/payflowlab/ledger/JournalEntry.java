package com.payflowlab.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A posted journal entry. Every column is {@code updatable = false}: the
 * application never issues an UPDATE, and a database trigger rejects one if
 * anything else tries.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Human-friendly running number assigned by a database sequence. */
    @Generated(event = EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long entryNumber;

    @Column(nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String sourceType;

    @Column(nullable = false, updatable = false)
    private UUID sourceId;

    @Column(updatable = false)
    private UUID merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(nullable = false, updatable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    @BatchSize(size = 50)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    public JournalEntry(String eventType, String sourceType, UUID sourceId, UUID merchantId, String currency,
                        String description, Instant postedAt) {
        this.eventType = eventType;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.merchantId = merchantId;
        this.currency = currency;
        this.description = description;
        this.postedAt = postedAt;
    }

    void addLine(Account account, Account.Side side, long amountMinor) {
        lines.add(new JournalLine(this, (short) (lines.size() + 1), account, side, amountMinor));
    }

    public long totalMinor() {
        return lines.stream().filter(l -> l.getSide() == Account.Side.DEBIT).mapToLong(JournalLine::getAmountMinor)
                .sum();
    }

    public UUID getId() {
        return id;
    }

    public Long getEntryNumber() {
        return entryNumber;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public List<JournalLine> getLines() {
        return lines;
    }
}
