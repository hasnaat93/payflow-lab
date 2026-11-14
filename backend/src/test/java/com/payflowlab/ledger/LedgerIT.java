package com.payflowlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflowlab.common.ApiException;
import com.payflowlab.ledger.LedgerService.Line;
import com.payflowlab.ledger.LedgerService.Posting;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.support.IntegrationTest;
import com.payflowlab.transaction.Transaction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIT extends IntegrationTest {

    @Autowired
    private LedgerService ledger;
    @Autowired
    private TransactionTemplate tx;

    private Posting posting(UUID sourceId, long debit, long credit) {
        return new Posting("TEST_EVENT", "TEST", sourceId, null, "USD", "test posting", List.of(
                Line.debit(Account.NETWORK_RECEIVABLE, debit),
                Line.credit(Account.MERCHANT_PAYABLE, credit)));
    }

    @Test
    void everyEntryProducedByThePaymentLifecycleBalances() {
        Merchant merchant = merchant("USD");
        Transaction txn = captured(merchant, 12_345);
        transactions.refund(txn.getId(), 2_000, null, unique("k"));
        transactions.refund(txn.getId(), 10_345, null, unique("k"));

        Integer unbalanced = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT journal_entry_id
                      FROM journal_lines
                     GROUP BY journal_entry_id
                    HAVING SUM(amount_minor) FILTER (WHERE side = 'DEBIT')
                        IS DISTINCT FROM SUM(amount_minor) FILTER (WHERE side = 'CREDIT')
                ) x""", Integer.class);

        assertThat(unbalanced).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE merchant_id = ?", Integer.class,
                merchant.getId())).isEqualTo(3);
    }

    @Test
    void serviceRejectsAnUnbalancedPosting() {
        assertThatThrownBy(() -> tx.execute(status -> ledger.post(posting(UUID.randomUUID(), 1_000, 999))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void sameBusinessEventCannotBePostedTwice() {
        UUID source = UUID.randomUUID();
        tx.execute(status -> ledger.post(posting(source, 500, 500)));

        assertThatThrownBy(() -> tx.execute(status -> ledger.post(posting(source, 500, 500))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo("DUPLICATE_LEDGER_POSTING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE source_id = ?", Integer.class,
                source)).isEqualTo(1);
    }

    @Test
    void postingRequiresAnEnclosingTransaction() {
        assertThatThrownBy(() -> ledger.post(posting(UUID.randomUUID(), 100, 100)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void zeroValueLinesAreDroppedSoAZeroFeeStillPosts() {
        UUID source = UUID.randomUUID();
        JournalEntry entry = tx.execute(status -> ledger.post(new Posting("TEST_EVENT", "TEST", source, null, "USD",
                "zero fee", List.of(Line.debit(Account.NETWORK_RECEIVABLE, 700),
                Line.credit(Account.MERCHANT_PAYABLE, 700), Line.credit(Account.FEE_REVENUE, 0)))));

        assertThat(entry.getLines()).hasSize(2);
        assertThat(entry.getEntryNumber()).isNotNull();
    }

    // ---- the database enforces the same rules without the application's help ----

    @Test
    void databaseRejectsAnUnbalancedEntryAtCommit() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("""
                    INSERT INTO journal_entries (id, event_type, source_type, source_id, currency, description, posted_at)
                    VALUES (?, 'RAW', 'TEST', ?, 'USD', 'raw insert', now())""", entryId, UUID.randomUUID());
            insertLine(entryId, 1, "1000", "DEBIT", 1_000);
            insertLine(entryId, 2, "2000", "CREDIT", 900);
            return null;
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("unbalanced");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE id = ?", Integer.class, entryId))
                .isZero();
    }

    @Test
    void databaseRejectsASingleLineEntry() {
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("""
                    INSERT INTO journal_entries (id, event_type, source_type, source_id, currency, description, posted_at)
                    VALUES (?, 'RAW', 'TEST', ?, 'USD', 'raw insert', now())""", entryId, UUID.randomUUID());
            insertLine(entryId, 1, "1000", "DEBIT", 1_000);
            return null;
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("unbalanced");
    }

    @Test
    void databaseRejectsUpdatesAndDeletesOfPostedLines() {
        UUID source = UUID.randomUUID();
        JournalEntry entry = tx.execute(status -> ledger.post(posting(source, 500, 500)));

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_lines SET amount_minor = 1 WHERE journal_entry_id = ?",
                entry.getId())).isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_lines WHERE journal_entry_id = ?", entry.getId()))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET description = 'edited' WHERE id = ?",
                entry.getId())).isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entries WHERE id = ?", entry.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void accountBalancesAreDerivedFromJournalLines() throws Exception {
        captured(merchant("USD"), 10_000);

        Response accounts = httpGet("/api/v1/ledger/accounts");

        assertThat(accounts.status()).isEqualTo(200);
        assertThat(accounts.<List<String>>read("$[*].code")).contains("1000", "2000", "2100", "2200", "4000");
        Long debits = jdbc.queryForObject("SELECT SUM(debit_minor) FROM rpt_account_balances", Long.class);
        Long credits = jdbc.queryForObject("SELECT SUM(credit_minor) FROM rpt_account_balances", Long.class);
        assertThat(debits).isEqualTo(credits);
    }

    private void insertLine(UUID entryId, int lineNo, String accountCode, String side, long amount) {
        jdbc.update("""
                INSERT INTO journal_lines (id, journal_entry_id, line_no, account_id, side, amount_minor)
                SELECT ?, ?, ?, id, ?, ? FROM accounts WHERE code = ?""",
                UUID.randomUUID(), entryId, lineNo, side, amount, accountCode);
    }
}
