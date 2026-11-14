package com.payflowlab.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.merchant.Merchant;
import com.payflowlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyAndConcurrencyIT extends IntegrationTest {

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheOriginalAndCreatesNothingNew() throws Exception {
        Merchant merchant = merchant("USD");
        String key = unique("key");
        String body = authorizeJson(merchant, 10_000, "4242");

        Response first = httpPost("/api/v1/transactions", body, key);
        Response second = httpPost("/api/v1/transactions", body, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(second.<String>read("$.id")).isEqualTo(first.<String>read("$.id"));
        assertThat(countTransactions(merchant)).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentPayloadIsRejected() throws Exception {
        Merchant merchant = merchant("USD");
        String key = unique("key");
        httpPost("/api/v1/transactions", authorizeJson(merchant, 10_000, "4242"), key);

        Response conflict = httpPost("/api/v1/transactions", authorizeJson(merchant, 99_999, "4242"), key);

        assertThat(conflict.status()).isEqualTo(422);
        assertThat(conflict.<String>read("$.errorCode")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(countTransactions(merchant)).isEqualTo(1);
    }

    @Test
    void authorizationWithoutAnIdempotencyKeyIsRejected() throws Exception {
        Response response = httpPost("/api/v1/transactions", authorizeJson(merchant("USD"), 100, "4242"), null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("MISSING_HEADER");
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        Response response = httpPost("/api/v1/transactions", authorizeJson(merchant("USD"), 100, "4242"),
                "has spaces and <symbols>");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void replayedRefundDoesNotRefundTwice() throws Exception {
        String id = captured(merchant("USD"), 10_000).getId().toString();
        String key = unique("key");
        String url = "/api/v1/transactions/" + id + "/refunds";

        Response first = httpPost(url, "{\"amountMinor\":4000,\"reason\":\"damaged\"}", key);
        Response replay = httpPost(url, "{\"amountMinor\":4000,\"reason\":\"damaged\"}", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.readLong("$.refundedAmountMinor")).isEqualTo(4_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE transaction_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void replayedCaptureWithAKeyIsSafe() throws Exception {
        String id = authorized(merchant("USD"), 10_000).getId().toString();
        String key = unique("key");

        Response first = httpPost("/api/v1/transactions/" + id + "/capture", null, key);
        Response replay = httpPost("/api/v1/transactions/" + id + "/capture", null, key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fee_assessments WHERE transaction_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    /** Eight identical requests arrive at once: exactly one payment may exist afterwards. */
    @Test
    void concurrentDuplicateAuthorizationsCreateExactlyOneTransaction() throws Exception {
        Merchant merchant = merchant("USD");
        String key = unique("key");
        var command = new TransactionService.AuthorizeCommand(merchant.getId(), 10_000, "USD", "VISA", "4242",
                null, AuthorizationSimulator.Scenario.AUTO);

        runConcurrently(8, () -> transactions.authorize(command, key));

        assertThat(countTransactions(merchant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    /** Ten refunds of 3,000 race for a 10,000 capture: only three can ever fit. */
    @Test
    void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
        Transaction txn = captured(merchant("USD"), 10_000);

        int succeeded = runConcurrently(10, () -> transactions.refund(txn.getId(), 3_000, "race", unique("key")));

        assertThat(succeeded).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT refunded_amount_minor FROM transactions WHERE id = ?", Long.class,
                txn.getId())).isEqualTo(9_000);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE transaction_id = ?",
                Long.class, txn.getId())).isEqualTo(9_000);
    }

    /** A capture and a reversal race for the same authorization: one wins, never both. */
    @Test
    void captureAndReversalCannotBothSucceed() throws Exception {
        Transaction txn = authorized(merchant("USD"), 10_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> attempt(start, successes, () -> transactions.capture(txn.getId(), null))),
                    pool.submit(() -> attempt(start, successes, () -> transactions.reverse(txn.getId(), null))));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        String status = jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, txn.getId());
        int ledgerEntries = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE source_id = ?",
                Integer.class, txn.getId());
        assertThat(status).isIn("CAPTURED", "REVERSED");
        assertThat(ledgerEntries).isEqualTo(status.equals("CAPTURED") ? 1 : 0);
    }

    private int countTransactions(Merchant merchant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE merchant_id = ?", Integer.class,
                merchant.getId());
    }

    private static void attempt(CountDownLatch start, AtomicInteger successes, Callable<?> work) {
        try {
            start.await();
            work.call();
            successes.incrementAndGet();
        } catch (Exception expectedForTheLoser) {
            // the loser of a race is rejected; that is the behaviour under test
        }
    }

    /** @return how many of the concurrent calls completed without an exception */
    private static int runConcurrently(int threads, Callable<?> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> attempt(start, successes, work)));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return successes.get();
    }
}
