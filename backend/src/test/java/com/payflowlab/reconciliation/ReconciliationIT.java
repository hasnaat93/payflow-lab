package com.payflowlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.demo.DemoDataSeeder;
import com.payflowlab.settlement.SettlementService;
import com.payflowlab.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationIT extends IntegrationTest {

    @Autowired
    private SettlementService settlement;
    @Autowired
    private DemoDataSeeder seeder;

    private final String today = LocalDate.now(ZoneOffset.UTC).toString();

    /** Statements cover every completed batch in a period, so this class starts from an empty database. */
    @BeforeEach
    void sixSettledMerchants() {
        seeder.wipe();
        for (int i = 0; i < 6; i++) {
            captured(merchant("USD"), 10_000 + i * 1_000L);
        }
        settlement.run(null, null);
    }

    private String generate(String scenario) throws Exception {
        Response statement = httpPost("/api/v1/reconciliation/statements/generate",
                "{\"periodStart\":\"%s\",\"periodEnd\":\"%s\",\"scenario\":\"%s\"}".formatted(today, today, scenario),
                null);
        assertThat(statement.status()).isEqualTo(201);
        return statement.read("$.id");
    }

    private Response reconcile(String statementId) throws Exception {
        return httpPost("/api/v1/reconciliation/runs", "{\"statementId\":\"" + statementId + "\"}", null);
    }

    @Test
    void cleanStatementMatchesEverything() throws Exception {
        Response run = reconcile(generate("CLEAN"));

        assertThat(run.status()).isEqualTo(201);
        assertThat(run.<Integer>read("$.run.totalItems")).isEqualTo(6);
        assertThat(run.<Integer>read("$.run.matchedCount")).isEqualTo(6);
        assertThat(run.<Integer>read("$.run.exceptionCount")).isZero();
        assertThat(run.<Double>read("$.run.matchRatePercent")).isEqualTo(100.0);
        assertThat(run.<List<String>>read("$.items[*].reviewStatus")).containsOnly("NOT_REQUIRED");
    }

    @Test
    void anomaliesAreDetectedAndNeverSilentlyMatched() throws Exception {
        Response run = reconcile(generate("WITH_ANOMALIES"));

        // 6 batches: 3 untouched, 1 short-paid, 1 omitted, 1 late; plus 1 unknown statement line
        assertThat(run.<Integer>read("$.run.totalItems")).isEqualTo(7);
        assertThat(run.<Integer>read("$.run.matchedCount")).isEqualTo(3);
        assertThat(run.<Integer>read("$.run.amountMismatchCount")).isEqualTo(1);
        assertThat(run.<Integer>read("$.run.unmatchedInternalCount")).isEqualTo(1);
        assertThat(run.<Integer>read("$.run.dateMismatchCount")).isEqualTo(1);
        assertThat(run.<Integer>read("$.run.unmatchedExternalCount")).isEqualTo(1);
        assertThat(run.<List<Integer>>read("$.items[?(@.outcome=='AMOUNT_MISMATCH')].differenceMinor"))
                .containsExactly(-250);
        assertThat(run.<List<String>>read("$.items[?(@.outcome=='UNMATCHED_EXTERNAL')].merchantCode"))
                .containsExactly("UNKNOWN-MERCHANT");
        assertThat(run.<List<String>>read("$.items[?(@.outcome!='MATCHED')].reviewStatus")).containsOnly("OPEN");
    }

    @Test
    void operatorReviewsAnExceptionWithANote() throws Exception {
        String runId = reconcile(generate("WITH_ANOMALIES")).read("$.run.id");
        Response queue = httpGet("/api/v1/reconciliation/exceptions?reviewStatus=OPEN&runId=" + runId);
        assertThat(queue.readLong("$.totalItems")).isEqualTo(4);
        String itemId = queue.read("$.items[0].id");
        String outcomeBefore = queue.read("$.items[0].outcome");

        Response reviewed = httpPost("/api/v1/reconciliation/exceptions/" + itemId + "/review",
                "{\"resolutionNote\":\"Bank confirmed a correction on the next statement\"}", null);

        assertThat(reviewed.status()).isEqualTo(200);
        assertThat(reviewed.<String>read("$.reviewStatus")).isEqualTo("REVIEWED");
        assertThat(reviewed.<String>read("$.resolutionNote")).startsWith("Bank confirmed");
        assertThat(reviewed.<String>read("$.reviewedAt")).isNotNull();
        // reviewing documents the exception; it must not turn a mismatch into a match
        assertThat(reviewed.<String>read("$.outcome")).isEqualTo(outcomeBefore);
        assertThat(httpGet("/api/v1/reconciliation/exceptions?reviewStatus=OPEN&runId=" + runId)
                .readLong("$.totalItems")).isEqualTo(3);
        assertThat(httpGet("/api/v1/reconciliation/runs/" + runId).<Integer>read("$.run.matchedCount")).isEqualTo(3);
    }

    @Test
    void anExceptionCannotBeReviewedTwiceAndNeedsARealNote() throws Exception {
        String runId = reconcile(generate("WITH_ANOMALIES")).read("$.run.id");
        String itemId = httpGet("/api/v1/reconciliation/exceptions?runId=" + runId).read("$.items[0].id");
        String url = "/api/v1/reconciliation/exceptions/" + itemId + "/review";

        Response tooShort = httpPost(url, "{\"resolutionNote\":\"ok\"}", null);
        httpPost(url, "{\"resolutionNote\":\"Investigated and accepted\"}", null);
        Response again = httpPost(url, "{\"resolutionNote\":\"Reviewing a second time\"}", null);

        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.<String>read("$.errorCode")).isEqualTo("VALIDATION_FAILED");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.<String>read("$.errorCode")).isEqualTo("EXCEPTION_NOT_OPEN");
    }

    @Test
    void matchedItemsCannotBeReviewed() throws Exception {
        Response run = reconcile(generate("CLEAN"));
        String matchedItem = run.read("$.items[0].id");

        Response review = httpPost("/api/v1/reconciliation/exceptions/" + matchedItem + "/review",
                "{\"resolutionNote\":\"Nothing to review here\"}", null);

        assertThat(review.status()).isEqualTo(409);
    }

    @Test
    void reRunningKeepsHistoryInsteadOfOverwritingIt() throws Exception {
        String statementId = generate("WITH_ANOMALIES");
        String firstRun = reconcile(statementId).read("$.run.id");
        String secondRun = reconcile(statementId).read("$.run.id");

        Response history = httpGet("/api/v1/reconciliation/runs");

        assertThat(firstRun).isNotEqualTo(secondRun);
        assertThat(history.readLong("$.totalItems")).isEqualTo(2);
        assertThat(httpGet("/api/v1/reconciliation/runs/" + firstRun).<Integer>read("$.run.totalItems")).isEqualTo(7);
        assertThat(httpGet("/api/v1/reconciliation/statements/" + statementId + "/lines")
                .<List<Object>>read("$")).hasSize(6);
    }

    @Test
    void statementNeedsCompletedBatchesInThePeriod() throws Exception {
        Response response = httpPost("/api/v1/reconciliation/statements/generate",
                "{\"periodStart\":\"2001-01-01\",\"periodEnd\":\"2001-01-31\"}", null);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("NO_COMPLETED_BATCHES");
    }
}
