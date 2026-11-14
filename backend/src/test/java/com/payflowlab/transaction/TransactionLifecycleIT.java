package com.payflowlab.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.fee.FeePlan;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransactionLifecycleIT extends IntegrationTest {

    @Test
    void ordinaryCardIsAuthorized() throws Exception {
        Merchant merchant = merchant("USD");

        Response response = httpPost("/api/v1/transactions", authorizeJson(merchant, 10_000, "4242"), unique("k"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.<String>read("$.status")).isEqualTo("AUTHORIZED");
        assertThat(response.<String>read("$.merchantCode")).isEqualTo(merchant.getMerchantCode());
        assertThat(response.readLong("$.amountMinor")).isEqualTo(10_000);
        assertThat(response.readLong("$.capturedAmountMinor")).isZero();
        assertThat(response.header("Idempotent-Replay")).isEqualTo("false");
    }

    @Test
    void magicCardIsDeclinedAndTheDeclineIsARecordedTransaction() throws Exception {
        Merchant merchant = merchant("USD");

        Response response = httpPost("/api/v1/transactions", authorizeJson(merchant, 10_000, "0002"), unique("k"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.<String>read("$.status")).isEqualTo("DECLINED");
        assertThat(response.<String>read("$.declineReason")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void forcedScenarioOverridesTheCardRules() throws Exception {
        Merchant merchant = merchant("USD");
        String body = """
                {"merchantId":"%s","amountMinor":500,"currency":"USD","cardBrand":"VISA","cardLast4":"4242",
                 "scenario":"FORCE_DECLINE"}""".formatted(merchant.getId());

        Response response = httpPost("/api/v1/transactions", body, unique("k"));

        assertThat(response.<String>read("$.declineReason")).isEqualTo("SIMULATOR_FORCED_DECLINE");
    }

    @Test
    void captureAssessesTheFeeAndPostsABalancedLedgerEntry() throws Exception {
        Merchant merchant = merchant("USD"); // 2.90% + 0.30
        String id = authorized(merchant, 10_000).getId().toString();

        Response capture = httpPost("/api/v1/transactions/" + id + "/capture", null, null);

        assertThat(capture.status()).isEqualTo(200);
        assertThat(capture.<String>read("$.status")).isEqualTo("CAPTURED");
        assertThat(capture.readLong("$.feeMinor")).isEqualTo(320);
        assertThat(capture.readLong("$.netMinor")).isEqualTo(9_680);

        Response detail = httpGet("/api/v1/transactions/" + id);
        assertThat(detail.readLong("$.feeAssessment.percentageComponentMinor")).isEqualTo(290);
        assertThat(detail.readLong("$.feeAssessment.fixedComponentMinor")).isEqualTo(30);
        assertThat(detail.readLong("$.feeAssessment.totalFeeMinor")).isEqualTo(320);
        assertThat(detail.<List<String>>read("$.events[*].eventType"))
                .containsExactly("AUTHORIZATION_APPROVED", "CAPTURED");

        List<Map<String, Object>> lines = detail.read("$.journalEntries[0].lines");
        assertThat(detail.<List<Object>>read("$.journalEntries")).hasSize(1);
        assertThat(lines).extracting(l -> l.get("accountCode") + " " + l.get("side") + " " + l.get("amountMinor"))
                .containsExactly("1000 DEBIT 10000", "2000 CREDIT 9680", "4000 CREDIT 320");
    }

    @Test
    void capturingTwiceIsRejected() throws Exception {
        String id = captured(merchant("USD"), 5_000).getId().toString();

        Response second = httpPost("/api/v1/transactions/" + id + "/capture", null, null);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.<String>read("$.errorCode")).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE source_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void declinedTransactionCannotBeCapturedReversedOrRefunded() throws Exception {
        Merchant merchant = merchant("USD");
        String id = httpPost("/api/v1/transactions", authorizeJson(merchant, 4_000, "0051"), unique("k"))
                .read("$.id");

        assertThat(httpPost("/api/v1/transactions/" + id + "/capture", null, null).status()).isEqualTo(409);
        assertThat(httpPost("/api/v1/transactions/" + id + "/reverse", null, null).status()).isEqualTo(409);
        assertThat(httpPost("/api/v1/transactions/" + id + "/refunds", "{\"amountMinor\":100}", unique("k"))
                .status()).isEqualTo(409);
    }

    @Test
    void authorizationCanBeReversedAndMovesNoMoney() throws Exception {
        String id = authorized(merchant("USD"), 8_000).getId().toString();

        Response reverse = httpPost("/api/v1/transactions/" + id + "/reverse", null, null);

        assertThat(reverse.status()).isEqualTo(200);
        assertThat(reverse.<String>read("$.status")).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE source_id = ?::uuid",
                Integer.class, id)).isZero();
        // a reversed authorization is terminal
        assertThat(httpPost("/api/v1/transactions/" + id + "/capture", null, null).status()).isEqualTo(409);
    }

    @Test
    void capturedTransactionCannotBeReversed() throws Exception {
        String id = captured(merchant("USD"), 8_000).getId().toString();

        Response reverse = httpPost("/api/v1/transactions/" + id + "/reverse", null, null);

        assertThat(reverse.status()).isEqualTo(409);
        assertThat(reverse.<String>read("$.errorCode")).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void partialRefundsAccumulateUntilFullyRefunded() throws Exception {
        String id = captured(merchant("USD"), 10_000).getId().toString();
        String url = "/api/v1/transactions/" + id + "/refunds";

        Response first = httpPost(url, "{\"amountMinor\":3000,\"reason\":\"item returned\"}", unique("k"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.<String>read("$.status")).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(first.readLong("$.refundableMinor")).isEqualTo(7_000);

        Response second = httpPost(url, "{\"amountMinor\":7000}", unique("k"));
        assertThat(second.<String>read("$.status")).isEqualTo("REFUNDED");
        assertThat(second.readLong("$.refundedAmountMinor")).isEqualTo(10_000);

        Response third = httpPost(url, "{\"amountMinor\":1}", unique("k"));
        assertThat(third.status()).isEqualTo(409);

        Response detail = httpGet("/api/v1/transactions/" + id);
        assertThat(detail.<List<Object>>read("$.refunds")).hasSize(2);
        // capture entry + one entry per refund
        assertThat(detail.<List<Object>>read("$.journalEntries")).hasSize(3);
    }

    @Test
    void cumulativeRefundsCannotExceedTheCapturedAmount() throws Exception {
        String id = captured(merchant("USD"), 10_000).getId().toString();
        String url = "/api/v1/transactions/" + id + "/refunds";
        httpPost(url, "{\"amountMinor\":6000}", unique("k"));

        Response over = httpPost(url, "{\"amountMinor\":4001}", unique("k"));

        assertThat(over.status()).isEqualTo(422);
        assertThat(over.<String>read("$.errorCode")).isEqualTo("REFUND_EXCEEDS_CAPTURED");
        assertThat(httpGet("/api/v1/transactions/" + id).readLong("$.transaction.refundedAmountMinor"))
                .isEqualTo(6_000);
        // the exact remaining amount is still refundable
        assertThat(httpPost(url, "{\"amountMinor\":4000}", unique("k")).<String>read("$.status"))
                .isEqualTo("REFUNDED");
    }

    @Test
    void uncapturedAuthorizationCannotBeRefunded() throws Exception {
        String id = authorized(merchant("USD"), 5_000).getId().toString();

        Response refund = httpPost("/api/v1/transactions/" + id + "/refunds", "{\"amountMinor\":100}", unique("k"));

        assertThat(refund.status()).isEqualTo(409);
        assertThat(refund.<String>read("$.errorCode")).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void suspendedMerchantCannotAcceptPayments() throws Exception {
        Merchant merchant = merchant("USD");
        merchants.changeStatus(merchant.getId(), Merchant.Status.SUSPENDED);

        Response response = httpPost("/api/v1/transactions", authorizeJson(merchant, 1_000, "4242"), unique("k"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("MERCHANT_SUSPENDED");
    }

    @Test
    void currencyMustMatchTheMerchant() throws Exception {
        Merchant merchant = merchant("USD");
        String body = authorizeJson(merchant, 1_000, "4242").replace("\"USD\"", "\"EUR\"");

        Response response = httpPost("/api/v1/transactions", body, unique("k"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void editingAFeePlanDoesNotChangeFeesAlreadyAssessed() throws Exception {
        FeePlan plan = standardPlan("USD");
        Merchant merchant = merchant(plan);
        String before = captured(merchant, 10_000).getId().toString();

        feePlans.update(plan.getId(), "Raised", 500, 100, null, null, true); // now 5.00% + 1.00
        String after = captured(merchant, 10_000).getId().toString();

        Response old = httpGet("/api/v1/transactions/" + before);
        assertThat(old.readLong("$.feeAssessment.totalFeeMinor")).isEqualTo(320);
        assertThat(old.<Integer>read("$.feeAssessment.percentageBps")).isEqualTo(290);
        assertThat(httpGet("/api/v1/transactions/" + after).readLong("$.feeAssessment.totalFeeMinor"))
                .isEqualTo(600);
    }

    @Test
    void searchFiltersByMerchantStatusAndReference() throws Exception {
        Merchant merchant = merchant("USD");
        captured(merchant, 1_000);
        captured(merchant, 2_000);
        String reference = authorized(merchant, 3_000).getExternalReference();

        Response all = httpGet("/api/v1/transactions?merchantId=" + merchant.getId());
        Response onlyCaptured = httpGet("/api/v1/transactions?status=CAPTURED&merchantId=" + merchant.getId());
        Response byReference = httpGet("/api/v1/transactions?q=" + reference.substring(4).toLowerCase());

        assertThat(all.readLong("$.totalItems")).isEqualTo(3);
        assertThat(onlyCaptured.readLong("$.totalItems")).isEqualTo(2);
        assertThat(byReference.<List<String>>read("$.items[*].externalReference")).containsExactly(reference);
    }
}
