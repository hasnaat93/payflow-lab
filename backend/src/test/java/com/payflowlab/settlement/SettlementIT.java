package com.payflowlab.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflowlab.merchant.Merchant;
import com.payflowlab.support.IntegrationTest;
import com.payflowlab.transaction.Transaction;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class SettlementIT extends IntegrationTest {

    @Autowired
    private SettlementService settlement;

    @Test
    void batchTotalsCapturesFeesAndRefunds() {
        Merchant merchant = merchant("USD"); // 2.90% + 0.30
        Transaction first = captured(merchant, 10_000);  // fee 320
        captured(merchant, 20_000);                      // fee 610
        captured(merchant, 5_000);                       // fee 175
        transactions.refund(first.getId(), 2_500, "partial", unique("k"));
        authorized(merchant, 99_000);                    // not captured: must not settle

        SettlementService.RunResult result = settlement.run(null, merchant.getId());

        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        SettlementBatch batch = result.batches().get(0);
        assertThat(batch.getStatus()).isEqualTo(SettlementBatch.Status.COMPLETED);
        assertThat(batch.getTransactionCount()).isEqualTo(3);
        assertThat(batch.getRefundCount()).isEqualTo(1);
        assertThat(batch.getGrossMinor()).isEqualTo(35_000);
        assertThat(batch.getFeeMinor()).isEqualTo(1_105);
        assertThat(batch.getRefundMinor()).isEqualTo(2_500);
        assertThat(batch.getNetMinor()).isEqualTo(31_395);
        assertThat(batch.getBatchReference()).startsWith("STL-").endsWith(merchant.getMerchantCode() + "-01");
        assertThat(count("SELECT COUNT(*) FROM settlement_batch_lines WHERE batch_id = ?", batch.getId())).isEqualTo(4);
    }

    @Test
    void settlementClearsTheMerchantPayableBalance() {
        Merchant merchant = merchant("USD");
        Transaction txn = captured(merchant, 40_000);
        captured(merchant, 15_000);
        transactions.refund(txn.getId(), 4_000, null, unique("k"));

        assertThat(balance(merchant, "2000")).isEqualTo(55_000 - 1_190 - 465 - 4_000);

        settlement.run(null, merchant.getId());

        assertThat(balance(merchant, "2000")).isZero();           // nothing left owed to the merchant
        assertThat(balance(merchant, "2200")).isZero();           // refunds cleared
        assertThat(balance(merchant, "2100")).isEqualTo(49_345);  // payout in transit
        assertThat(balance(merchant, "4000")).isEqualTo(1_655);   // fees earned
    }

    @Test
    void runningAgainSettlesNothingTwice() {
        Merchant merchant = merchant("USD");
        captured(merchant, 10_000);
        settlement.run(null, merchant.getId());

        SettlementService.RunResult second = settlement.run(null, merchant.getId());

        assertThat(second.batches()).isEmpty();
        assertThat(second.summary()).contains("Nothing to settle");
        assertThat(count("SELECT COUNT(*) FROM settlement_batches WHERE merchant_id = ?", merchant.getId()))
                .isEqualTo(1);
    }

    @Test
    void laterRunPicksUpOnlyNewActivity() {
        Merchant merchant = merchant("USD");
        captured(merchant, 10_000);
        settlement.run(null, merchant.getId());
        captured(merchant, 7_000);

        SettlementBatch second = settlement.run(null, merchant.getId()).batches().get(0);

        assertThat(second.getTransactionCount()).isEqualTo(1);
        assertThat(second.getGrossMinor()).isEqualTo(7_000);
        assertThat(second.getBatchReference()).endsWith("-02");
    }

    /** Six runs start at the same instant for one merchant: its transactions settle exactly once. */
    @Test
    void concurrentRunsNeverSettleATransactionTwice() throws Exception {
        Merchant merchant = merchant("USD");
        for (int i = 0; i < 5; i++) {
            captured(merchant, 10_000 + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SettlementService.RunResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return settlement.run(null, merchant.getId());
                }));
            }
            start.countDown();
            for (Future<SettlementService.RunResult> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM settlement_batches WHERE merchant_id = ? AND status = 'COMPLETED'",
                merchant.getId())).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM settlement_batch_lines l
                  JOIN transactions t ON t.id = l.transaction_id WHERE t.merchant_id = ?""", merchant.getId()))
                .isEqualTo(5);
        assertThat(count("""
                SELECT COUNT(*) FROM (
                    SELECT l.transaction_id FROM settlement_batch_lines l
                      JOIN transactions t ON t.id = l.transaction_id
                     WHERE t.merchant_id = ? AND l.line_type = 'CAPTURE'
                     GROUP BY l.transaction_id HAVING COUNT(*) > 1) dup""", merchant.getId())).isZero();
        assertThat(balance(merchant, "2000")).isZero();
    }

    @Test
    void databaseRefusesASecondBatchLineForTheSameCapture() {
        Merchant merchant = merchant("USD");
        Transaction txn = captured(merchant, 10_000);
        SettlementBatch batch = settlement.run(null, merchant.getId()).batches().get(0);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO settlement_batch_lines (id, batch_id, line_type, transaction_id, gross_minor, fee_minor, net_minor)
                VALUES (?, ?, 'CAPTURE', ?, 10000, 320, 9680)""", UUID.randomUUID(), batch.getId(), txn.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("uq_batch_lines_capture");
    }

    /** Refunds that outweigh captures cannot be paid out: the batch fails and everything carries forward. */
    @Test
    void negativeNetFailsTheBatchAndCarriesItemsForward() {
        Merchant merchant = merchant("USD");
        Transaction txn = captured(merchant, 10_000);
        settlement.run(null, merchant.getId());
        transactions.refund(txn.getId(), 10_000, "full refund after payout", unique("k"));

        SettlementBatch failed = settlement.run(null, merchant.getId()).batches().get(0);

        assertThat(failed.getStatus()).isEqualTo(SettlementBatch.Status.FAILED);
        assertThat(failed.getFailureReason()).startsWith("NET_NOT_POSITIVE");
        assertThat(failed.getNetMinor()).isEqualTo(-10_000);
        assertThat(count("SELECT COUNT(*) FROM settlement_batch_lines WHERE batch_id = ?", failed.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE transaction_id = ? AND settlement_batch_id IS NULL",
                txn.getId())).isEqualTo(1);

        captured(merchant, 50_000); // fee 1480
        SettlementBatch recovered = settlement.run(null, merchant.getId()).batches().get(0);

        assertThat(recovered.getStatus()).isEqualTo(SettlementBatch.Status.COMPLETED);
        assertThat(recovered.getRefundMinor()).isEqualTo(10_000);
        assertThat(recovered.getNetMinor()).isEqualTo(50_000 - 1_480 - 10_000);
        assertThat(balance(merchant, "2000")).isZero();
    }

    @Test
    void settlementDateCannotBeInTheFuture() throws Exception {
        Response response = httpPost("/api/v1/settlement-batches/run", "{\"settlementDate\":\"2999-01-01\"}", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("SETTLEMENT_DATE_IN_FUTURE");
    }

    @Test
    void batchDetailExposesLinesAndLedgerEntriesOverHttp() throws Exception {
        Merchant merchant = merchant("USD");
        Transaction txn = captured(merchant, 10_000);
        transactions.refund(txn.getId(), 1_000, null, unique("k"));

        Response run = httpPost("/api/v1/settlement-batches/run",
                "{\"merchantId\":\"" + merchant.getId() + "\"}", null);
        String batchId = run.read("$.batches[0].id");
        Response detail = httpGet("/api/v1/settlement-batches/" + batchId);

        assertThat(run.status()).isEqualTo(200);
        assertThat(run.<Integer>read("$.completed")).isEqualTo(1);
        assertThat(detail.<List<String>>read("$.lines[*].lineType")).containsExactly("CAPTURE", "REFUND");
        assertThat(detail.<List<String>>read("$.journalEntries[*].eventType"))
                .containsExactlyInAnyOrder("SETTLEMENT_PAYOUT", "REFUND_SETTLEMENT");
        assertThat(httpGet("/api/v1/transactions/" + txn.getId()).<String>read("$.transaction.settlementBatchId"))
                .isEqualTo(batchId);
        assertThat(httpGet("/api/v1/settlement-batches?merchantId=" + merchant.getId()).readLong("$.totalItems"))
                .isEqualTo(1);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Balance of one account for one merchant, signed for the account's normal (credit) side. */
    private long balance(Merchant merchant, String accountCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN jl.amount_minor ELSE -jl.amount_minor END), 0)
                  FROM journal_lines jl
                  JOIN journal_entries je ON je.id = jl.journal_entry_id
                  JOIN accounts a ON a.id = jl.account_id
                 WHERE je.merchant_id = ? AND a.code = ?""", Long.class, merchant.getId(), accountCode);
    }
}
