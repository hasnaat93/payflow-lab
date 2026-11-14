package com.payflowlab.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.payflowlab.fee.FeePlan;
import com.payflowlab.fee.FeePlanService;
import com.payflowlab.merchant.Merchant;
import com.payflowlab.merchant.MerchantService;
import com.payflowlab.transaction.AuthorizationSimulator;
import com.payflowlab.transaction.Transaction;
import com.payflowlab.transaction.TransactionService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class for integration tests: full application context, real PostgreSQL,
 * HTTP through MockMvc. Tests never share fixtures; each one creates its own
 * merchant with a unique code, so they are independent of execution order.
 */
@SpringBootTest(properties = {
        "payflow.demo.seed-on-startup=false",
        "payflow.demo.reset-enabled=true"
})
@AutoConfigureMockMvc
@Import(EmbeddedPostgresConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected FeePlanService feePlans;
    @Autowired
    protected MerchantService merchants;
    @Autowired
    protected TransactionService transactions;

    /** HTTP status plus the parsed JSON body. */
    public record Response(int status, DocumentContext json, MvcResult raw) {

        public <T> T read(String path) {
            return json.read(path);
        }

        public long readLong(String path) {
            return ((Number) json.read(path)).longValue();
        }

        public String header(String name) {
            return raw.getResponse().getHeader(name);
        }
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ fixtures

    /** 2.90% + 0.30 in the given currency: the plan most tests reason about. */
    protected FeePlan standardPlan(String currency) {
        return feePlans.create(unique("PLAN"), "Test plan", currency, 290, 30, null, null);
    }

    protected Merchant merchant(String currency) {
        return merchants.create(unique("M"), "Test Merchant", null, currency, standardPlan(currency).getId());
    }

    protected Merchant merchant(FeePlan plan) {
        return merchants.create(unique("M"), "Test Merchant", null, plan.getCurrency(), plan.getId());
    }

    protected Transaction authorized(Merchant merchant, long amountMinor) {
        return transactions.authorize(new TransactionService.AuthorizeCommand(merchant.getId(), amountMinor,
                merchant.getCurrency(), "VISA", "4242", null, AuthorizationSimulator.Scenario.AUTO),
                unique("key")).transaction();
    }

    protected Transaction captured(Merchant merchant, long amountMinor) {
        return transactions.capture(authorized(merchant, amountMinor).getId(), null).transaction();
    }

    // ---------------------------------------------------------------- http

    protected Response httpGet(String url) throws Exception {
        return send(get(url));
    }

    protected Response httpPost(String url, String jsonBody, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post(url);
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request);
    }

    protected Response httpPut(String url, String jsonBody) throws Exception {
        return send(put(url).contentType(MediaType.APPLICATION_JSON).content(jsonBody));
    }

    protected Response send(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(),
                body.isBlank() || !body.trim().startsWith("{") && !body.trim().startsWith("[")
                        ? JsonPath.parse("{}") : JsonPath.parse(body),
                result);
    }

    protected String authorizeJson(Merchant merchant, long amountMinor, String last4) {
        return """
                {"merchantId":"%s","amountMinor":%d,"currency":"%s","cardBrand":"VISA","cardLast4":"%s"}
                """.formatted(merchant.getId(), amountMinor, merchant.getCurrency(), last4);
    }
}
