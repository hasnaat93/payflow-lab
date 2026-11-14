package com.payflowlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflowlab.fee.FeePlan;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The API's cross-cutting behaviour: validation, the error envelope, paging, and merchant/fee management. */
class ApiContractIT extends IntegrationTest {

    @Test
    void validationErrorsListEveryOffendingField() throws Exception {
        Response response = httpPost("/api/v1/merchants",
                "{\"merchantCode\":\"bad code\",\"displayName\":\"\",\"currency\":\"usd\"}", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("VALIDATION_FAILED");
        assertThat(response.<Integer>read("$.status")).isEqualTo(400);
        assertThat(response.<String>read("$.path")).isEqualTo("/api/v1/merchants");
        assertThat(response.<String>read("$.timestamp")).isNotBlank();
        assertThat(response.<List<String>>read("$.validationErrors[*].field"))
                .contains("merchantCode", "displayName", "currency", "feePlanId");
    }

    @Test
    void amountsMustBePositiveIntegersOfMinorUnits() throws Exception {
        Merchant merchant = merchant("USD");

        Response negative = httpPost("/api/v1/transactions", authorizeJson(merchant, -5, "4242"), unique("k"));
        Response decimal = httpPost("/api/v1/transactions",
                authorizeJson(merchant, 100, "4242").replace("\"amountMinor\":100", "\"amountMinor\":10.50"),
                unique("k"));

        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.<List<String>>read("$.validationErrors[*].field")).contains("amountMinor");
        assertThat(decimal.status()).isEqualTo(400); // money is never accepted as a fraction
    }

    @Test
    void cardNumbersAreNotAcceptedByTheApi() throws Exception {
        Merchant merchant = merchant("USD");
        String withPan = authorizeJson(merchant, 1_000, "4242").trim().replaceFirst("}$",
                ",\"cardNumber\":\"4111111111111111\",\"cvv\":\"123\"}");

        Response response = httpPost("/api/v1/transactions", withPan, unique("k"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("MALFORMED_REQUEST");
        assertThat(response.raw().getResponse().getContentAsString()).doesNotContain("4111111111111111");
    }

    @Test
    void cardLastFourMustBeExactlyFourDigits() throws Exception {
        Response response = httpPost("/api/v1/transactions", authorizeJson(merchant("USD"), 1_000, "42424"),
                unique("k"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<List<String>>read("$.validationErrors[*].field")).containsExactly("cardLast4");
    }

    @Test
    void malformedJsonAndBadIdentifiersGetACleanError() throws Exception {
        Response malformed = httpPost("/api/v1/merchants", "{not json", null);
        Response badUuid = httpGet("/api/v1/transactions/not-a-uuid");
        Response missing = httpGet("/api/v1/transactions/00000000-0000-0000-0000-000000000000");
        Response noEndpoint = httpGet("/api/v1/does-not-exist");

        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.<String>read("$.errorCode")).isEqualTo("MALFORMED_REQUEST");
        assertThat(badUuid.status()).isEqualTo(400);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.<String>read("$.errorCode")).isEqualTo("NOT_FOUND");
        assertThat(noEndpoint.status()).isEqualTo(404);
        for (Response response : List.of(malformed, badUuid, missing, noEndpoint)) {
            String body = response.raw().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Exception").doesNotContain("org.").doesNotContain("SQL");
        }
    }

    @Test
    void pagingAndSortingAreBounded() throws Exception {
        assertThat(httpGet("/api/v1/transactions?size=1000").<String>read("$.errorCode")).isEqualTo("INVALID_PAGING");
        assertThat(httpGet("/api/v1/transactions?page=-1").status()).isEqualTo(400);
        assertThat(httpGet("/api/v1/transactions?sort=cardToken,asc").<String>read("$.errorCode"))
                .isEqualTo("INVALID_SORT");

        Response ok = httpGet("/api/v1/transactions?size=5&sort=amountMinor,asc");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.<Integer>read("$.size")).isEqualTo(5);
    }

    @Test
    void merchantCanBeCreatedSearchedUpdatedSuspendedAndReactivated() throws Exception {
        FeePlan plan = standardPlan("USD");
        FeePlan otherPlan = standardPlan("USD");
        String code = unique("SHOP");
        String create = "{\"merchantCode\":\"%s\",\"displayName\":\"Corner Shop\",\"currency\":\"USD\",\"feePlanId\":\"%s\"}"
                .formatted(code, plan.getId());

        Response created = httpPost("/api/v1/merchants", create, null);
        String id = created.read("$.id");
        Response duplicate = httpPost("/api/v1/merchants", create, null);
        Response found = httpGet("/api/v1/merchants?q=" + code.toLowerCase());
        Response updated = httpPut("/api/v1/merchants/" + id,
                "{\"displayName\":\"Corner Shop Ltd\",\"feePlanId\":\"%s\"}".formatted(otherPlan.getId()));
        Response suspended = httpPost("/api/v1/merchants/" + id + "/suspend", null, null);
        Response suspendedAgain = httpPost("/api/v1/merchants/" + id + "/suspend", null, null);
        Response onlySuspended = httpGet("/api/v1/merchants?status=SUSPENDED&q=" + code);
        Response activated = httpPost("/api/v1/merchants/" + id + "/activate", null, null);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>read("$.status")).isEqualTo("ACTIVE");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.<String>read("$.errorCode")).isEqualTo("MERCHANT_CODE_TAKEN");
        assertThat(found.<List<String>>read("$.items[*].merchantCode")).containsExactly(code);
        assertThat(updated.<String>read("$.displayName")).isEqualTo("Corner Shop Ltd");
        assertThat(updated.<String>read("$.feePlanCode")).isEqualTo(otherPlan.getCode());
        assertThat(suspended.<String>read("$.status")).isEqualTo("SUSPENDED");
        assertThat(suspendedAgain.status()).isEqualTo(409);
        assertThat(onlySuspended.readLong("$.totalItems")).isEqualTo(1);
        assertThat(activated.<String>read("$.status")).isEqualTo("ACTIVE");
    }

    @Test
    void merchantCannotUseAFeePlanInAnotherCurrency() throws Exception {
        FeePlan eurPlan = standardPlan("EUR");

        Response response = httpPost("/api/v1/merchants",
                "{\"merchantCode\":\"%s\",\"displayName\":\"Shop\",\"currency\":\"USD\",\"feePlanId\":\"%s\"}"
                        .formatted(unique("SHOP"), eurPlan.getId()), null);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.<String>read("$.errorCode")).isEqualTo("FEE_PLAN_CURRENCY_MISMATCH");
    }

    @Test
    void feePlanCanBeCreatedPreviewedAndValidated() throws Exception {
        String code = unique("PLAN");
        Response created = httpPost("/api/v1/fee-plans",
                "{\"code\":\"%s\",\"name\":\"Tiered\",\"currency\":\"USD\",\"percentageBps\":250,\"fixedFeeMinor\":20,\"minFeeMinor\":50,\"maxFeeMinor\":1000}"
                        .formatted(code), null);
        String id = created.read("$.id");

        Response small = httpGet("/api/v1/fee-plans/" + id + "/preview?amountMinor=100");
        Response large = httpGet("/api/v1/fee-plans/" + id + "/preview?amountMinor=1000000");
        Response invertedBounds = httpPost("/api/v1/fee-plans",
                "{\"code\":\"%s\",\"name\":\"Bad\",\"currency\":\"USD\",\"percentageBps\":100,\"fixedFeeMinor\":0,\"minFeeMinor\":500,\"maxFeeMinor\":100}"
                        .formatted(unique("PLAN")), null);
        Response tooHigh = httpPost("/api/v1/fee-plans",
                "{\"code\":\"%s\",\"name\":\"Bad\",\"currency\":\"USD\",\"percentageBps\":10001,\"fixedFeeMinor\":0}"
                        .formatted(unique("PLAN")), null);

        assertThat(created.status()).isEqualTo(201);
        assertThat(small.<String>read("$.adjustment")).isEqualTo("MINIMUM_APPLIED");
        assertThat(small.readLong("$.totalFeeMinor")).isEqualTo(50);
        assertThat(large.<String>read("$.adjustment")).isEqualTo("MAXIMUM_APPLIED");
        assertThat(large.readLong("$.totalFeeMinor")).isEqualTo(1_000);
        assertThat(invertedBounds.<String>read("$.errorCode")).isEqualTo("INVALID_FEE_BOUNDS");
        assertThat(tooHigh.status()).isEqualTo(400);
    }

    @Test
    void auditTrailRecordsThePaymentLifecycle() throws Exception {
        Merchant merchant = merchant("USD");
        String id = captured(merchant, 10_000).getId().toString();
        transactions.refund(java.util.UUID.fromString(id), 1_000, null, unique("k"));

        Response audit = httpGet("/api/v1/audit-events?entityType=TRANSACTION&entityId=" + id);

        assertThat(audit.<List<String>>read("$.items[*].action")).containsExactlyInAnyOrder(
                "TRANSACTION_CREATED", "AUTHORIZATION_APPROVED", "FEE_ASSESSED", "TRANSACTION_CAPTURED",
                "TRANSACTION_REFUNDED");
        // audit metadata carries identifiers and amounts, never card data
        assertThat(audit.raw().getResponse().getContentAsString()).doesNotContain("tok_demo").doesNotContain("4242");
    }

    @Test
    void healthSimulatorRulesAndOpenApiAreServed() throws Exception {
        Response health = httpGet("/actuator/health");
        Response rules = httpGet("/api/v1/simulator/rules");
        Response openApi = httpGet("/v3/api-docs");
        Response demo = httpGet("/api/v1/demo/status");

        assertThat(health.<String>read("$.status")).isEqualTo("UP");
        assertThat(rules.<List<Object>>read("$")).hasSize(7);
        assertThat(openApi.status()).isEqualTo(200);
        assertThat(openApi.<String>read("$.info.title")).isEqualTo("PayFlow Lab API");
        assertThat(openApi.<java.util.Map<String, Object>>read("$.paths"))
                .containsKeys("/api/v1/transactions", "/api/v1/settlement-batches/run",
                        "/api/v1/reconciliation/runs", "/api/v1/dashboard/summary");
        assertThat(demo.<Boolean>read("$.simulator")).isTrue();
    }
}
