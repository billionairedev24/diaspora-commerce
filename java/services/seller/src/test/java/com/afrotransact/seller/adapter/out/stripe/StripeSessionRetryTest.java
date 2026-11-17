package com.afrotransact.seller.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.GatewayException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seller's first Stripe step creates the account and, moments later, its account session. Stripe can answer "no such
 * account" for a second, or 5xx / 429: the session call retries those a bounded number of times so the first attempt works,
 * and still fails at once on a real refusal.
 */
class StripeSessionRetryTest {

    private static final String SESSION_OK = "{\"object\":\"account_session\",\"account\":\"acct_1\",\"client_secret\":"
            + "\"accs_ok\",\"expires_at\":4102444800,\"livemode\":false}";
    private static final String NO_SUCH_ACCOUNT = "{\"error\":{\"type\":\"invalid_request_error\",\"code\":"
            + "\"resource_missing\",\"message\":\"No such account: 'acct_1'\",\"param\":\"account\"}}";

    private static WireMockServer stripe;
    private StripeConnectAdapter adapter;

    @BeforeAll
    static void start() {
        stripe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripe.start();
    }

    @AfterAll
    static void stop() {
        stripe.stop();
    }

    @BeforeEach
    void wire() {
        stripe.resetAll();
        StripeClient client = StripeClient.builder().setApiKey("sk_test_retry").setApiBase(stripe.baseUrl())
                .setConnectBase(stripe.baseUrl()).setMaxNetworkRetries(0).build();
        adapter = new StripeConnectAdapter(client, JsonMapper.builder().build(), 2, 1);
    }

    private void answers(int status, String body) {
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/account_sessions")).willReturn(WireMock.aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void thenSucceedsAfter(int status, String body, int failures) {
        String scenario = "retry";
        for (int i = 0; i < failures; i++) {
            stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/account_sessions")).inScenario(scenario)
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "f" + i)
                    .willReturn(WireMock.aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                            .withBody(body)).willSetStateTo("f" + (i + 1)));
        }
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/account_sessions")).inScenario(scenario)
                .whenScenarioStateIs("f" + failures).willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(SESSION_OK)));
    }

    private int sessionCalls() {
        return stripe.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/account_sessions"))).size();
    }

    @Test
    void aSessionForAnAccountStripeDoesNotShowYetSucceedsOnTheNextAttempt() {
        thenSucceedsAfter(404, NO_SUCH_ACCOUNT, 1);
        AccountSession s = adapter.createAccountSession("acct_1", Set.of("account_onboarding"));
        assertThat(s.clientSecret()).isEqualTo("accs_ok");
        assertThat(sessionCalls()).isEqualTo(2);
    }

    @Test
    void stripeBeingBusyOrDownForAMomentIsRetried() {
        thenSucceedsAfter(503, "{\"error\":{\"type\":\"api_error\",\"message\":\"try later\"}}", 2);
        assertThat(adapter.createAccountSession("acct_1", Set.of("account_onboarding")).clientSecret())
                .isEqualTo("accs_ok");
        assertThat(sessionCalls()).isEqualTo(3);

        stripe.resetAll();
        thenSucceedsAfter(429, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"rate_limit\","
                + "\"message\":\"slow down\"}}", 1);
        assertThat(adapter.createAccountSession("acct_1", Set.of("account_onboarding")).clientSecret())
                .isEqualTo("accs_ok");
    }

    @Test
    void retriesAreBoundedAndTheFailureIsReportedAsUnavailable() {
        answers(503, "{\"error\":{\"type\":\"api_error\",\"message\":\"down\"}}");
        assertThatThrownBy(() -> adapter.createAccountSession("acct_1", Set.of("account_onboarding")))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.stripe-unavailable");
                    assertThat(e.rejected()).isFalse();
                });
        assertThat(sessionCalls()).isEqualTo(3);
    }

    @Test
    void aRealRefusalIsNotRetried() {
        answers(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Invalid components\"}}");
        assertThatThrownBy(() -> adapter.createAccountSession("acct_1", Set.of("account_onboarding")))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.code())
                        .isEqualTo("seller.stripe-rejected"));
        assertThat(sessionCalls()).isEqualTo(1);

        stripe.resetAll();
        answers(401, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Invalid API Key\"}}");
        assertThatThrownBy(() -> adapter.createAccountSession("acct_1", Set.of("account_onboarding")))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.code()).isEqualTo("seller.stripe-key"));
        assertThat(sessionCalls()).isEqualTo(1);
    }
}
