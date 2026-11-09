package com.afrotransact.seller.adapter.in.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afrotransact.seller.adapter.out.stripe.StripeWebhookAdapter;
import com.afrotransact.seller.application.StripeWebhookService;
import com.afrotransact.seller.application.WebhookResult;
import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.StripeWebhookParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two public Stripe webhooks of docs/runbooks/stripe-webhooks.md, without a database or Docker: the real signature
 * check (stripe-java, 5-minute tolerance, one secret per endpoint) behind the real controller and error advice. Unsigned
 * and badly signed requests answer 400 (never 404: that is the runbook's route check), a correctly signed event is
 * acknowledged. Inbox and handlers are covered by ComplianceWebhookIT and BillingIT.
 */
class WebhookControllerTest {

    private static final String CONNECT_SECRET = "whsec_connect_test";
    private static final String BILLING_SECRET = "whsec_billing_test";
    private static final String CONNECT_URL = "/v1/webhooks/stripe-connect";
    private static final String BILLING_URL = "/v1/webhooks/stripe-billing";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StripeWebhookParser parser = new StripeWebhookAdapter(CONNECT_SECRET, BILLING_SECRET, JsonMapper.builder().build());
        StripeWebhookService service = (endpoint, payload, signature) -> {
            StripeEvent e = parser.parse(endpoint, payload, signature);
            return new WebhookResult(e.id(), e.type(), false, "applied");
        };
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(service)).setControllerAdvice(new SellerErrorAdvice())
                .build();
    }

    @Test
    void unsignedRequestsAreRejectedWith400OnBothEndpoints() throws Exception {
        for (String url : new String[] {CONNECT_URL, BILLING_URL}) {
            send(url, event("evt_1", "account.updated"), null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("seller.invalid-signature")));
        }
    }

    @Test
    void wrongSecretTamperedBodyAndStaleTimestampAreRejected() throws Exception {
        String body = event("evt_2", "account.updated");
        send(CONNECT_URL, body, header(body, "whsec_other", now())).andExpect(status().isBadRequest());
        send(CONNECT_URL, body + " ", header(body, CONNECT_SECRET, now())).andExpect(status().isBadRequest());
        send(CONNECT_URL, body, header(body, CONNECT_SECRET, now() - 600)).andExpect(status().isBadRequest());
        // each endpoint has its own secret: the Connect secret does not open the billing endpoint
        send(BILLING_URL, body, header(body, CONNECT_SECRET, now())).andExpect(status().isBadRequest());
        send(CONNECT_URL, body, "garbage").andExpect(status().isBadRequest());
    }

    @Test
    void correctlySignedEventsAreAcknowledged() throws Exception {
        String connect = event("evt_3", "account.updated");
        send(CONNECT_URL, connect, header(connect, CONNECT_SECRET, now())).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId", is("evt_3"))).andExpect(jsonPath("$.type", is("account.updated")));
        String billing = event("evt_4", "payment_intent.succeeded");
        send(BILLING_URL, billing, header(billing, BILLING_SECRET, now())).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId", is("evt_4")));
    }

    @Test
    void anEndpointWithoutASecretRejectsEverything() throws Exception {
        StripeWebhookParser unset = new StripeWebhookAdapter("", "", JsonMapper.builder().build());
        MockMvc bare = MockMvcBuilders.standaloneSetup(new WebhookController((ep, p, s) -> {
            unset.parse(ep, p, s);
            return new WebhookResult("x", "x", false, "x");
        })).setControllerAdvice(new SellerErrorAdvice()).build();
        String body = event("evt_5", "account.updated");
        bare.perform(post(CONNECT_URL).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Stripe-Signature", header(body, CONNECT_SECRET, now()))).andExpect(status().isBadRequest());
    }

    private ResultActions send(String url, String body, String signature) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(signature == null ? req : req.header("Stripe-Signature", signature));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String event(String id, String type) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"type\":\"" + type + "\",\"created\":" + now()
                + ",\"data\":{\"object\":{\"id\":\"acct_1\",\"object\":\"account\"}}}";
    }

    private static String header(String payload, String secret, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + v1;
    }
}
