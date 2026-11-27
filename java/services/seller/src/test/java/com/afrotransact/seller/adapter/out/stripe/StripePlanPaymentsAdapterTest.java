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
 * Plan fees are paid by card, US bank debit (ACH) or from earnings: the SetupIntent offers card and us_bank_account
 * (Financial Connections instant verification, microdeposits as the fallback), the off-session PaymentIntent allows
 * both; an off-session charge that needs 3-D Secure is REQUIRES_ACTION (the seller confirms it), and staff refunds carry
 * the idempotency key. Docker-free (WireMock).
 */
class StripePlanPaymentsAdapterTest {

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
        stripe.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/setup_intents/seti_1")).willReturn(json(
                "{\"id\":\"seti_1\",\"object\":\"setup_intent\",\"status\":\"succeeded\",\"payment_method\":\"pm_ach\","
                        + "\"mandate\":\"mandate_1\"}")));
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents")).willReturn(json(
                "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"status\":\"processing\"}")));
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("payment_method=pm_3ds")).atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(402).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"card_error\",\"code\":\"authentication_required\","
                                + "\"decline_code\":\"authentication_required\",\"message\":\"Authenticate.\","
                                + "\"payment_intent\":{\"id\":\"pi_3ds\",\"object\":\"payment_intent\"}}}")));
        stripe.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/payment_intents/pi_3ds")).willReturn(json(
                "{\"id\":\"pi_3ds\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\","
                        + "\"client_secret\":\"pi_3ds_secret_x\",\"last_payment_error\":{\"type\":\"card_error\","
                        + "\"code\":\"authentication_required\",\"payment_method\":{\"id\":\"pm_3ds\","
                        + "\"object\":\"payment_method\"}}}")));
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/refunds")).willReturn(json(
                "{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"succeeded\",\"amount\":1000}")));
        StripeClient client = StripeClient.builder().setApiKey("sk_test_plan").setApiBase(stripe.baseUrl())
                .setMaxNetworkRetries(0).build();
        adapter = new StripePlanPaymentsAdapter(client);
    }

    @Test
    void theSetupIntentOffersCardAndUsBankAccountWithInstantVerification() {
        adapter.createSetupIntent("cus_1", "setup-1");
        assertThat(body("/v1/setup_intents")).contains("allowed_payment_method_types[0]=card",
                "allowed_payment_method_types[1]=us_bank_account",
                "payment_method_options[us_bank_account][verification_method]=automatic",
                "payment_method_options[us_bank_account][financial_connections][permissions][0]=payment_method",
                "usage=off_session", "metadata[purpose]=seller_billing");
        PlanPaymentsGateway.SetupResult setup = adapter.setupIntent("seti_1");
        assertThat(setup.status()).isEqualTo("succeeded");
        assertThat(setup.mandateId()).isEqualTo("mandate_1");
    }

    @Test
    void thePlanChargeAllowsCardAndBankDebitAndABankDebitIsProcessing() {
        PlanPaymentsGateway.Charge charge = adapter.charge("cus_1", "pm_ach", 3900, "USD", "inv_1", "AfroTransact plan",
                "inv-inv_1-a0");
        assertThat(charge.status()).isEqualTo(PlanPaymentsGateway.Status.PROCESSING);
        assertThat(body("/v1/payment_intents")).contains("allowed_payment_method_types[0]=card",
                "allowed_payment_method_types[1]=us_bank_account", "off_session=true", "metadata[purpose]=plan_fee",
                "metadata[invoice_id]=inv_1");
    }

    @Test
    void anOffSessionChargeThatNeedsThreeDSecureIsRequiresActionWithItsClientSecret() {
        PlanPaymentsGateway.Charge charge = adapter.charge("cus_1", "pm_3ds", 3900, "USD", "inv_1", "plan", "k");
        assertThat(charge.status()).isEqualTo(PlanPaymentsGateway.Status.REQUIRES_ACTION);
        assertThat(charge.reference()).isEqualTo("pi_3ds");

        PlanPaymentsGateway.Charge read = adapter.paymentStatus("pi_3ds");
        assertThat(read.status()).isEqualTo(PlanPaymentsGateway.Status.REQUIRES_ACTION);
        assertThat(read.clientSecret()).isEqualTo("pi_3ds_secret_x");
        assertThat(read.paymentMethodId()).isEqualTo("pm_3ds");
    }

    @Test
    void aRefundIsPartialAndCarriesTheIdempotencyKey() {
        PlanPaymentsGateway.RefundResult r = adapter.refund("pi_1", 1000, "GOODWILL", "plan-refund-inv_1-k1");
        assertThat(r.id()).isEqualTo("re_1");
        assertThat(r.status()).isEqualTo("succeeded");
        assertThat(body("/v1/refunds")).contains("payment_intent=pi_1", "amount=1000", "metadata[purpose]=plan_fee");
        assertThat(stripe.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))).get(0)
                .getHeader("Idempotency-Key")).isEqualTo("plan-refund-inv_1-k1");
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
