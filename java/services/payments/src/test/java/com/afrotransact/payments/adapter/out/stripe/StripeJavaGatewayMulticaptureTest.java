package com.afrotransact.payments.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGatewayException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "This account is not eligible for the requested card features" (Stripe flexible payments): checkout intents only ask
 * for multicapture when configured, fall back to a single-capture intent when Stripe refuses it, and single-capture
 * captures send no final_capture.
 */
class StripeJavaGatewayMulticaptureTest {

    private static final String NOT_ELIGIBLE = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"This account "
            + "is not eligible for the requested card features. See https://stripe.com/docs/payments/flexible-payments "
            + "for more details.\"}}";

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private StripeJavaGateway gateway(boolean multicapture) {
        StripeClient client = StripeClient.builder().setApiKey("sk_test_unit").setApiBase(server.baseUrl())
                .setMaxNetworkRetries(0).build();
        return new StripeJavaGateway(client, multicapture);
    }

    private static String intent(String id, boolean multicapture) {
        String options = multicapture ? ",\"payment_method_options\":{\"card\":{\"request_multicapture\":\"if_available\"}}"
                : ",\"payment_method_options\":{\"card\":{}}";
        return "{\"id\":\"" + id + "\",\"object\":\"payment_intent\",\"amount\":4599,\"amount_capturable\":0,"
                + "\"amount_received\":0,\"status\":\"requires_payment_method\",\"client_secret\":\"" + id
                + "_secret_x\"" + options + "}";
    }

    private void stubCreate(String key, int status, String body) {
        server.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(key))
                .willReturn(WireMock.aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static StripeGateway.CreateIntent order() {
        return new StripeGateway.CreateIntent(Money.of(4599, "USD"), null, List.of(), "AfroTransact order o-1",
                Map.of("order_id", "o-1"), "o-1", false);
    }

    private List<LoggedRequest> creates() {
        return server.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents")));
    }

    @Test
    void withoutMulticaptureTheIntentIsASingleCaptureOneWithItsOwnKey() {
        stubCreate("o-1:create-intent:single", 200, intent("pi_single", false));

        StripeGateway.IntentSnapshot pi = gateway(false).createIntent(order(), "o-1:create-intent");

        assertThat(pi.id()).isEqualTo("pi_single");
        assertThat(pi.multicapture()).isFalse();
        assertThat(creates()).singleElement().satisfies(r -> assertThat(r.getBodyAsString())
                .doesNotContain("request_multicapture").contains("capture_method=manual"));
    }

    @Test
    void anEligibleAccountGetsAMulticaptureIntent() {
        stubCreate("o-1:create-intent", 200, intent("pi_multi", true));

        StripeGateway.IntentSnapshot pi = gateway(true).createIntent(order(), "o-1:create-intent");

        assertThat(pi.multicapture()).isTrue();
        assertThat(creates()).singleElement().satisfies(r -> assertThat(r.getBodyAsString())
                .contains("payment_method_options[card][request_multicapture]=if_available"));
    }

    @Test
    void anAccountWithoutFlexiblePaymentsFallsBackToASingleCaptureIntentAndStopsAsking() {
        stubCreate("o-1:create-intent", 400, NOT_ELIGIBLE);
        stubCreate("o-1:create-intent:single", 200, intent("pi_fallback", false));
        stubCreate("o-2:create-intent:single", 200, intent("pi_next", false));
        StripeJavaGateway gateway = gateway(true);

        StripeGateway.IntentSnapshot pi = gateway.createIntent(order(), "o-1:create-intent");
        StripeGateway.IntentSnapshot next = gateway.createIntent(order(), "o-2:create-intent");

        assertThat(pi.id()).isEqualTo("pi_fallback");
        assertThat(pi.multicapture()).isFalse();
        assertThat(next.id()).isEqualTo("pi_next");
        List<LoggedRequest> calls = creates();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).getBodyAsString()).contains("request_multicapture");
        assertThat(calls.get(1).getBodyAsString()).doesNotContain("request_multicapture");
        assertThat(calls.get(2).getBodyAsString()).doesNotContain("request_multicapture");
    }

    @Test
    void otherInvalidRequestsAreNotRetried() {
        stubCreate("o-1:create-intent", 400, "{\"error\":{\"type\":\"invalid_request_error\",\"param\":\"currency\","
                + "\"message\":\"Invalid currency: xyz\"}}");

        assertThatThrownBy(() -> gateway(true).createIntent(order(), "o-1:create-intent"))
                .isInstanceOf(StripeGatewayException.class).hasMessageContaining("Invalid currency");
        assertThat(creates()).hasSize(1);
    }

    @Test
    void aSingleCaptureSendsNoFinalCapture() {
        server.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents/pi_1/capture"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(intent("pi_1", false).replace("requires_payment_method", "succeeded"))));

        gateway(false).capture("pi_1", Money.of(4599, "USD"), null, Map.of("order_id", "o-1"), "o-1:capture:s1");
        gateway(false).capture("pi_1", Money.of(100, "USD"), false, Map.of(), "o-1:capture:s2");

        List<LoggedRequest> captures = server.findAll(WireMock.postRequestedFor(
                WireMock.urlPathEqualTo("/v1/payment_intents/pi_1/capture")));
        assertThat(captures.get(0).getBodyAsString()).contains("amount_to_capture=4599").doesNotContain("final_capture");
        assertThat(captures.get(1).getBodyAsString()).contains("final_capture=false");
    }
}
