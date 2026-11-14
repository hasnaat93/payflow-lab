package com.payflowlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.payflowlab.demo.DemoDataSeeder;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.settlement.SettlementService;
import com.payflowlab.support.IntegrationTest;
import com.payflowlab.transaction.AuthorizationSimulator;
import com.payflowlab.transaction.Transaction;
import com.payflowlab.transaction.TransactionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Dashboard and report figures are checked against a small dataset whose totals are known by hand. */
class DashboardAndReportsIT extends IntegrationTest {

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private SettlementService settlement;

    private Merchant merchant;

    @BeforeEach
    void knownDataset() {
        seeder.wipe();
        merchant = merchant("EUR");                        // 2.90% + 0.30
        Transaction first = captured(merchant, 10_000);    // fee 320
        captured(merchant, 20_000);                        // fee 610
        captured(merchant, 5_000);                         // fee 175
        transactions.refund(first.getId(), 2_500, null, unique("k"));
        transactions.authorize(new TransactionService.AuthorizeCommand(merchant.getId(), 7_000, "EUR", "VISA",
                "0002", null, AuthorizationSimulator.Scenario.AUTO), unique("k"));          // declined
        transactions.reverse(authorized(merchant, 3_000).getId(), null);                    // reversed
    }

    @Test
    void kpisAreComputedFromPersistedRows() throws Exception {
        Response summary = httpGet("/api/v1/dashboard/summary?currency=EUR");

        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.readLong("$.kpis.totalTransactions")).isEqualTo(5);
        assertThat(summary.readLong("$.kpis.approvedCount")).isEqualTo(4);
        assertThat(summary.readLong("$.kpis.declinedCount")).isEqualTo(1);
        assertThat(summary.readLong("$.kpis.reversedCount")).isEqualTo(1);
        assertThat(summary.readLong("$.kpis.refundedCount")).isEqualTo(1);
        assertThat(summary.readLong("$.kpis.grossMinor")).isEqualTo(45_000);
        assertThat(summary.readLong("$.kpis.capturedMinor")).isEqualTo(35_000);
        assertThat(summary.readLong("$.kpis.refundMinor")).isEqualTo(2_500);
        assertThat(summary.readLong("$.kpis.feeMinor")).isEqualTo(1_105);
        assertThat(summary.readLong("$.kpis.unsettledMinor")).isEqualTo(31_395);
        assertThat(summary.readLong("$.kpis.netSettlementMinor")).isZero();
        assertThat(summary.<Double>read("$.kpis.approvalRatePercent")).isEqualTo(80.0);
        assertThat(summary.<List<Object>>read("$.series")).hasSize(14);
        assertThat(summary.readLong("$.series[13].transactionCount")).isEqualTo(5);
        assertThat(summary.readLong("$.series[0].transactionCount")).isZero();
    }

    @Test
    void settlementMovesMoneyFromUnsettledToSettled() throws Exception {
        settlement.run(null, null);

        Response summary = httpGet("/api/v1/dashboard/summary?currency=EUR");

        assertThat(summary.readLong("$.kpis.netSettlementMinor")).isEqualTo(31_395);
        assertThat(summary.readLong("$.kpis.unsettledMinor")).isZero();
        assertThat(summary.readLong("$.settlementBatches.COMPLETED")).isEqualTo(1);
        assertThat(summary.readLong("$.settlementBatches.FAILED")).isZero();
    }

    @Test
    void currenciesAreNeverCombined() throws Exception {
        captured(merchant("USD"), 99_900);

        Response eur = httpGet("/api/v1/dashboard/summary?currency=EUR");
        Response usd = httpGet("/api/v1/dashboard/summary?currency=USD");

        assertThat(eur.readLong("$.kpis.capturedMinor")).isEqualTo(35_000);
        assertThat(usd.readLong("$.kpis.capturedMinor")).isEqualTo(99_900);
        assertThat(usd.<List<String>>read("$.currencies")).containsExactly("EUR", "USD");
    }

    @Test
    void statusFilterNarrowsTheFigures() throws Exception {
        Response declined = httpGet("/api/v1/dashboard/summary?currency=EUR&status=DECLINED");

        assertThat(declined.readLong("$.kpis.totalTransactions")).isEqualTo(1);
        assertThat(declined.readLong("$.kpis.grossMinor")).isEqualTo(7_000);
        assertThat(declined.readLong("$.kpis.capturedMinor")).isZero();
    }

    @Test
    void invalidRangesAndCurrenciesAreRejected() throws Exception {
        assertThat(httpGet("/api/v1/dashboard/summary?from=2026-02-01&to=2026-01-01").status()).isEqualTo(400);
        assertThat(httpGet("/api/v1/dashboard/summary?from=2020-01-01&to=2026-01-01").status()).isEqualTo(400);
        assertThat(httpGet("/api/v1/dashboard/summary?currency=JPY").<String>read("$.errorCode"))
                .isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void merchantPerformanceReportMatchesTheDataset() throws Exception {
        Response report = httpGet("/api/v1/reports/merchant-performance?currency=EUR");

        assertThat(report.status()).isEqualTo(200);
        Map<String, Object> row = report.read("$.rows[0]");
        assertThat(report.<List<Object>>read("$.rows")).hasSize(1);
        assertThat(row.get("merchant_code")).isEqualTo(merchant.getMerchantCode());
        assertThat(((Number) row.get("transactions")).longValue()).isEqualTo(5);
        assertThat(((Number) row.get("captured_minor")).longValue()).isEqualTo(35_000);
        assertThat(((Number) row.get("fee_minor")).longValue()).isEqualTo(1_105);
        assertThat(((Number) row.get("approval_rate_pct")).doubleValue()).isEqualTo(80.0);
        assertThat(((Number) row.get("volume_share_pct")).doubleValue()).isEqualTo(100.0);
        assertThat(report.<String>read("$.sql")).contains("RANK() OVER");
        assertThat(report.<List<String>>read("$.columns[?(@.key=='captured_minor')].type")).containsExactly("money");
    }

    @Test
    void trialBalanceTotalRowProvesTheLedgerBalances() throws Exception {
        settlement.run(null, null);

        Response report = httpGet("/api/v1/reports/trial-balance?currency=EUR");

        List<Map<String, Object>> total = report.read("$.rows[?(@.account_code=='TOTAL')]");
        assertThat(total).hasSize(1);
        assertThat(((Number) total.get(0).get("debit_minor")).longValue())
                .isEqualTo(((Number) total.get(0).get("credit_minor")).longValue())
                .isPositive();
        assertThat(((Number) total.get(0).get("balance_minor")).longValue()).isZero();
    }

    @Test
    void everyReportInTheCatalogueRuns() throws Exception {
        settlement.run(null, null);
        captured(merchant, 4_000); // one capture left unsettled so the aging report has a row
        List<String> ids = httpGet("/api/v1/reports").read("$[*].id");

        assertThat(ids).hasSize(7);
        for (String id : ids) {
            Response report = httpGet("/api/v1/reports/" + id + "?currency=EUR");
            assertThat(report.status()).as(id).isEqualTo(200);
            assertThat(report.<List<Object>>read("$.columns")).as(id).isNotEmpty();
            assertThat(report.<List<Object>>read("$.rows")).as(id).isNotEmpty();
        }
    }

    @Test
    void csvExportIsDownloadable() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reports/merchant-performance/export?currency=EUR")).andReturn();
        String csv = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("volume_rank,merchant_code,merchant_name");
        assertThat(csv.lines().count()).isEqualTo(2);
        assertThat(csv).contains(merchant.getMerchantCode()).contains("35000");
    }

    @Test
    void unknownReportIsNotFound() throws Exception {
        Response response = httpGet("/api/v1/reports/..%2F..%2Fapplication");

        assertThat(response.status()).isIn(400, 404);
        assertThat(httpGet("/api/v1/reports/no-such-report").<String>read("$.errorCode")).isEqualTo("NOT_FOUND");
    }
}
