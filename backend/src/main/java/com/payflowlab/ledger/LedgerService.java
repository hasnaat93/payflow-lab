package com.payflowlab.ledger;

import com.payflowlab.audit.AuditService;
import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.ledger.LedgerRepositories.AccountRepository;
import com.payflowlab.ledger.LedgerRepositories.JournalEntryRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way anything reaches the ledger.
 *
 * <p>Invariants (each also enforced by the database, see V2__ledger.sql):
 * <ul>
 *   <li>an entry has at least two lines and total debits equal total credits</li>
 *   <li>one business event (source type + source id + event type) posts at most once</li>
 *   <li>posted entries are never changed; corrections are new compensating entries</li>
 * </ul>
 */
@Service
public class LedgerService {

    public record Line(String accountCode, Account.Side side, long amountMinor) {

        public static Line debit(String accountCode, long amountMinor) {
            return new Line(accountCode, Account.Side.DEBIT, amountMinor);
        }

        public static Line credit(String accountCode, long amountMinor) {
            return new Line(accountCode, Account.Side.CREDIT, amountMinor);
        }
    }

    public record Posting(String eventType, String sourceType, UUID sourceId, UUID merchantId, String currency,
                          String description, List<Line> lines) {
    }

    public record AccountBalance(UUID accountId, String code, String name, String type, String normalSide,
                                 String description, String currency, long debitMinor, long creditMinor,
                                 long balanceMinor) {
    }

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final AuditService audit;
    private final AppClock clock;
    private final JdbcTemplate jdbc;

    public LedgerService(JournalEntryRepository entries, AccountRepository accounts, AuditService audit,
                         AppClock clock, JdbcTemplate jdbc) {
        this.entries = entries;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /**
     * Posts one balanced journal entry. Must run inside the caller's transaction
     * so the money movement and its ledger entry commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry post(Posting posting) {
        // zero-value lines carry no information (e.g. a zero fee) and the schema forbids them
        List<Line> lines = posting.lines().stream().filter(line -> line.amountMinor() != 0).toList();

        if (lines.stream().anyMatch(line -> line.amountMinor() < 0)) {
            throw new IllegalStateException("Journal line amounts must be positive: " + posting.eventType());
        }
        long debits = sum(lines, Account.Side.DEBIT);
        long credits = sum(lines, Account.Side.CREDIT);
        if (lines.size() < 2 || debits != credits) {
            throw new IllegalStateException("Unbalanced posting for " + posting.eventType() + " "
                    + posting.sourceId() + ": debits=" + debits + " credits=" + credits);
        }
        if (entries.existsBySourceTypeAndSourceIdAndEventType(posting.sourceType(), posting.sourceId(),
                posting.eventType())) {
            throw ApiException.conflict("DUPLICATE_LEDGER_POSTING", posting.eventType() + " for "
                    + posting.sourceType() + " " + posting.sourceId() + " has already been posted");
        }

        JournalEntry entry = new JournalEntry(posting.eventType(), posting.sourceType(), posting.sourceId(),
                posting.merchantId(), posting.currency(), posting.description(), clock.now());
        for (Line line : lines) {
            Account account = accounts.findByCode(line.accountCode())
                    .orElseThrow(() -> new IllegalStateException("Unknown account " + line.accountCode()));
            entry.addLine(account, line.side(), line.amountMinor());
        }
        JournalEntry saved = entries.saveAndFlush(entry);
        audit.record("JOURNAL_ENTRY", saved.getId(), "LEDGER_POSTED", AuditService.SYSTEM,
                Map.of("eventType", posting.eventType(), "sourceType", posting.sourceType(),
                        "sourceId", posting.sourceId().toString(), "currency", posting.currency(),
                        "amountMinor", debits));
        return saved;
    }

    private static long sum(List<Line> lines, Account.Side side) {
        return lines.stream().filter(line -> line.side() == side).mapToLong(Line::amountMinor).sum();
    }

    @Transactional(readOnly = true)
    public JournalEntry get(UUID id) {
        JournalEntry entry = entries.findById(id).orElseThrow(() -> ApiException.notFound("Journal entry", id));
        entry.getLines().size(); // initialise inside the transaction
        return entry;
    }

    @Transactional(readOnly = true)
    public List<JournalEntry> forSource(String sourceType, UUID sourceId) {
        List<JournalEntry> found = entries.findBySourceTypeAndSourceIdOrderByPostedAtAsc(sourceType, sourceId);
        found.forEach(entry -> entry.getLines().size());
        return found;
    }

    @Transactional(readOnly = true)
    public Page<JournalEntry> search(String accountCode, String eventType, UUID merchantId, String currency,
                                     Instant from, Instant to, Pageable pageable) {
        Specification<JournalEntry> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (eventType != null && !eventType.isBlank()) {
                where.add(cb.equal(root.get("eventType"), eventType));
            }
            if (merchantId != null) {
                where.add(cb.equal(root.get("merchantId"), merchantId));
            }
            if (currency != null && !currency.isBlank()) {
                where.add(cb.equal(root.get("currency"), currency));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("postedAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("postedAt"), to));
            }
            if (accountCode != null && !accountCode.isBlank()) {
                Subquery<UUID> touching = query.subquery(UUID.class);
                Root<JournalLine> line = touching.from(JournalLine.class);
                touching.select(line.get("entry").get("id"))
                        .where(cb.equal(line.join("account", JoinType.INNER).get("code"), accountCode));
                where.add(root.get("id").in(touching));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<JournalEntry> page = entries.findAll(spec, pageable);
        page.forEach(entry -> entry.getLines().size());
        return page;
    }

    /** Balances are always derived from journal lines; no running balance is stored anywhere. */
    @Transactional(readOnly = true)
    public List<AccountBalance> balances() {
        return jdbc.query("""
                SELECT a.id, a.code, a.name, a.type, a.normal_side, a.description,
                       b.currency, COALESCE(b.debit_minor, 0) AS debit_minor,
                       COALESCE(b.credit_minor, 0) AS credit_minor, COALESCE(b.balance_minor, 0) AS balance_minor
                  FROM accounts a
                  LEFT JOIN rpt_account_balances b ON b.account_id = a.id
                 ORDER BY a.code, b.currency
                """, (rs, i) -> new AccountBalance(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("type"), rs.getString("normal_side"),
                rs.getString("description"), rs.getString("currency"), rs.getLong("debit_minor"),
                rs.getLong("credit_minor"), rs.getLong("balance_minor")));
    }
}
