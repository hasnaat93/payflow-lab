package com.payflowlab.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/** The seeded demo must be rich enough to show every feature and must obey every financial invariant. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoDataIT extends IntegrationTest {

    @Autowired
    private DemoDataSeeder seeder;

    @BeforeAll
    void resetThroughTheApi() throws Exception {
        assertThat(httpPost("/api/v1/demo/reset", null, null).status()).isEqualTo(200);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void demoIsBroadEnoughToBeUseful() {
        assertThat(count("SELECT COUNT(*) FROM merchants")).isGreaterThanOrEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM merchants WHERE status = 'SUSPENDED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM fee_plans")).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM transactions")).isGreaterThan(300);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM transactions", String.class))
                .containsExactlyInAnyOrder("AUTHORIZED", "DECLINED", "CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED",
                        "REVERSED");
        assertThat(jdbc.queryForList("SELECT DISTINCT currency FROM transactions", String.class))
                .containsExactlyInAnyOrder("USD", "EUR", "PKR");
        assertThat(count("SELECT COUNT(*) FROM audit_events")).isGreaterThan(1_000);
    }

    @Test
    void settlementHistoryIncludesCompletedAndFailedBatchesAndLeavesWorkPending() {
        assertThat(count("SELECT COUNT(*) FROM settlement_batches WHERE status = 'COMPLETED'")).isGreaterThan(20);
        assertThat(count("SELECT COUNT(*) FROM settlement_batches WHERE status = 'FAILED'")).isGreaterThanOrEqualTo(1);
        // yesterday and today are deliberately unsettled so "Run settlement" has something to do
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE captured_at IS NOT NULL AND settlement_batch_id IS NULL"))
                .isPositive();
    }

    @Test
    void reconciliationHistoryShowsEveryOutcome() {
        List<String> outcomes = jdbc.queryForList("SELECT DISTINCT outcome FROM reconciliation_items", String.class);

        assertThat(outcomes).contains("MATCHED", "AMOUNT_MISMATCH", "UNMATCHED_INTERNAL", "UNMATCHED_EXTERNAL");
        assertThat(count("SELECT COUNT(*) FROM reconciliation_runs")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM reconciliation_items WHERE review_status = 'OPEN'")).isPositive();
        assertThat(count("SELECT COUNT(*) FROM reconciliation_items WHERE review_status = 'REVIEWED'")).isEqualTo(1);
    }

    @Test
    void seededLedgerBalancesInEveryCurrency() {
        assertThat(count("""
                SELECT COUNT(*) FROM (
                    SELECT journal_entry_id FROM journal_lines GROUP BY journal_entry_id
                    HAVING SUM(amount_minor) FILTER (WHERE side = 'DEBIT')
                        IS DISTINCT FROM SUM(amount_minor) FILTER (WHERE side = 'CREDIT')) x""")).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM (
                    SELECT currency FROM rpt_account_balances GROUP BY currency
                    HAVING SUM(debit_minor) <> SUM(credit_minor)) x""")).isZero();
    }

    @Test
    void seededMoneyReconcilesBetweenTablesAndLedger() {
        // fee revenue in the ledger equals the fees recorded on transactions
        assertThat(count("SELECT COALESCE(SUM(balance_minor), 0) FROM rpt_account_balances WHERE code = '4000' AND currency = 'USD'"))
                .isEqualTo(count("SELECT COALESCE(SUM(fee_minor), 0) FROM transactions WHERE currency = 'USD'"));
        // what is still owed to merchants equals captured-but-unsettled net, less unsettled refunds
        assertThat(count("SELECT COALESCE(SUM(balance_minor), 0) FROM rpt_account_balances WHERE code = '2000' AND currency = 'USD'"))
                .isEqualTo(count("""
                        SELECT (SELECT COALESCE(SUM(captured_amount_minor - fee_minor), 0) FROM transactions
                                 WHERE currency = 'USD' AND captured_at IS NOT NULL AND settlement_batch_id IS NULL)
                             - (SELECT COALESCE(SUM(amount_minor), 0) FROM refunds
                                 WHERE currency = 'USD' AND settlement_batch_id IS NULL)"""));
        // completed batch totals equal their lines
        assertThat(count("""
                SELECT COUNT(*) FROM settlement_batches b
                 WHERE b.status = 'COMPLETED'
                   AND b.net_minor <> (SELECT COALESCE(SUM(l.net_minor), 0) FROM settlement_batch_lines l
                                        WHERE l.batch_id = b.id)""")).isZero();
    }

    @Test
    void nothingIsDatedInTheFutureOrRefundedBeyondItsCapture() {
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE created_at > now() OR captured_at > now()")).isZero();
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE created_at > now()")).isZero();
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE refunded_amount_minor > captured_amount_minor"))
                .isZero();
    }

    @Test
    void noCardDataBeyondBrandLastFourAndADemoTokenIsStored() {
        assertThat(count("SELECT COUNT(*) FROM transactions WHERE card_token NOT LIKE 'tok_demo_%'")).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND (column_name ILIKE '%pan%' OR column_name ILIKE '%cvv%' OR column_name ILIKE '%pin%'
                        OR column_name ILIKE '%card_number%' OR column_name ILIKE '%expiry%')""")).isZero();
    }

    @Test
    void resetIsRepeatable() {
        long before = count("SELECT COUNT(*) FROM transactions");

        seeder.reset();

        assertThat(count("SELECT COUNT(*) FROM transactions")).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM accounts")).isEqualTo(5);
    }
}
