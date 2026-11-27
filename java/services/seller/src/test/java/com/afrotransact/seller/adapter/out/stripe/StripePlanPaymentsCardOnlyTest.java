package com.afrotransact.seller.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan fees are paid by card (or from earnings), never by bank debit: the SetupIntent that saves a plan payment method
 * and the off-session PaymentIntent that charges it both allow only {@code card} (no automatic payment methods, so
 * {@code us_bank_account} cannot be offered or used). A charge Stripe leaves processing is read back with
 * {@link PlanPaymentsGateway#paymentStatus}, since there is no plan-fee webhook.
 */
class StripePlanPaymentsCardOnlyTest {

    private static WireMockServer stripe;
    private StripePlanPaymentsAdapter adapter;

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
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/setup_intents")).willReturn(json(
                "{\"id\":\"seti_1\",\"object\":\"setup_intent\",\"client_secret\":\"seti_1_secret\","
                        + "\"status\":\"requires_payment_method\"}")));
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents")).willReturn(json(
                "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"status\":\"processing\"}")));
        stripe.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/payment_intents/pi_1")).willReturn(json(
                "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\","
                        + "\"last_payment_error\":{\"type\":\"card_error\",\"code\":\"card_declined\","
                        + "\"message\":\"Your card was declined.\"}}")));
        StripeClient client = StripeClient.builder().setApiKey("sk_test_plan").setApiBase(stripe.baseUrl())
                .setMaxNetworkRetries(0).build();
        adapter = new StripePlanPaymentsAdapter(client);
    }

    @Test
    void theSetupIntentAllowsOnlyCards() {
        adapter.createSetupIntent("cus_1", "setup-1");
        String body = body("/v1/setup_intents");
        assertThat(body).contains("allowed_payment_method_types[0]=card", "usage=off_session")
                .doesNotContain("us_bank_account").doesNotContain("allowed_payment_method_types[1]")
                .doesNotContain("automatic_payment_methods");
    }

    @Test
    void thePlanChargeAllowsOnlyCardsAndAProcessingChargeIsReadBack() {
        PlanPaymentsGateway.Charge charge = adapter.charge("cus_1", "pm_1", 3900, "USD", "inv_1", "AfroTransact plan",
                "inv-inv_1-a0");
        assertThat(charge.status()).isEqualTo(PlanPaymentsGateway.Status.PROCESSING);
        assertThat(body("/v1/payment_intents")).contains("allowed_payment_method_types[0]=card", "off_session=true",
                "metadata[purpose]=plan_fee").doesNotContain("us_bank_account")
                .doesNotContain("automatic_payment_methods");

        PlanPaymentsGateway.Charge later = adapter.paymentStatus("pi_1");
        assertThat(later.status()).isEqualTo(PlanPaymentsGateway.Status.FAILED);
        assertThat(later.failureCode()).isEqualTo("card_declined");
        assertThat(later.failureMessage()).isEqualTo("Your card was declined.");
    }

    private static String body(String path) {
        List<LoggedRequest> sent = stripe.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(path)));
        assertThat(sent).hasSize(1);
        return URLDecoder.decode(sent.get(0).getBodyAsString(), StandardCharsets.UTF_8);
    }

    private static ResponseDefinitionBuilder json(String body) {
        return WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
