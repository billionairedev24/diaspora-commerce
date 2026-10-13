package com.afrotransact.payments.it;

import java.time.Instant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

/** WireMock stand-in for api.stripe.com (blocked in the sandbox). Each stub matches a test-unique id. */
final class StripeStubs {

    private final WireMockServer server;

    StripeStubs(WireMockServer server) {
        this.server = server;
    }

    private void json(MappingBuilder mapping, String body) {
        server.stubFor(mapping.willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    void createIntent(String orderId, String piId, long amount) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId + ":create-intent")),
                intent(piId, amount, "requires_payment_method", null, 0));
    }

    void retrieveIntent(String piId, long amount, String status) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/payment_intents/" + piId)),
                intent(piId, amount, status, null, 0));
    }

    void capture(String piId, String chargeId, long amount, String status) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents/" + piId + "/capture")),
                intent(piId, amount, status, chargeId, amount));
    }

    void cancel(String piId, long amount) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents/" + piId + "/cancel")),
                intent(piId, amount, "canceled", null, 0));
    }

    /** Off-session PaymentIntent of an off-session charge of {@code parentPaymentId}, confirmed with {@code status}. */
    void offSessionIntent(String parentPaymentId, String piId, long amount, String status) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("metadata[parent_payment_id]=" + parentPaymentId)),
                intent(piId, amount, status, null, 0));
    }

    /** The bank requires authentication: Stripe answers 402 card_error with the intent left in requires_payment_method. */
    void offSessionAuthenticationRequired(String parentPaymentId, String piId, long amount) {
        server.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("metadata[parent_payment_id]=" + parentPaymentId))
                .willReturn(WireMock.aResponse().withStatus(402).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"card_error\",\"code\":\"authentication_required\","
                                + "\"decline_code\":\"authentication_required\",\"message\":\"Your card was "
                                + "declined. This transaction requires authentication.\",\"payment_intent\":"
                                + intent(piId, amount, "requires_payment_method", null, 0) + "}}")));
    }

    /** Account debit (Charge with source = the connected account). */
    void accountDebit(String account, String debitId, long amount) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/charges"))
                .withRequestBody(WireMock.containing("source=" + account)),
                "{\"id\":\"" + debitId + "\",\"object\":\"charge\",\"amount\":" + amount
                        + ",\"currency\":\"usd\",\"status\":\"succeeded\"}");
    }

    /** Balance transactions of a charge: one per capture as {amount, fee} pairs. */
    void chargeFees(String chargeId, long[]... captures) {
        StringBuilder data = new StringBuilder();
        int i = 0;
        for (long[] c : captures) {
            if (i > 0) {
                data.append(',');
            }
            data.append(txn("txn_" + chargeId + "_" + i++, "charge", chargeId, c[0], c[1], Instant.now()));
        }
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/balance_transactions"))
                .withQueryParam("source", WireMock.equalTo(chargeId)), list(data.toString()));
    }

    void refund(String piId, String refundId, long amount, String status) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=" + piId)),
                "{\"id\":\"" + refundId + "\",\"object\":\"refund\",\"amount\":" + amount + ",\"currency\":\"usd\","
                        + "\"status\":\"" + status + "\",\"payment_intent\":\"" + piId + "\"}");
    }

    void transfer(String orderId, String account, String transferId, long amount) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + orderId))
                .withRequestBody(WireMock.containing("destination=" + account)),
                "{\"id\":\"" + transferId + "\",\"object\":\"transfer\",\"amount\":" + amount
                        + ",\"currency\":\"usd\",\"destination\":\"" + account + "\",\"transfer_group\":\"" + orderId
                        + "\"}");
    }

    void reversal(String transferId, String reversalId, long amount) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/transfers/" + transferId + "/reversals")),
                "{\"id\":\"" + reversalId + "\",\"object\":\"transfer_reversal\",\"amount\":" + amount
                        + ",\"currency\":\"usd\",\"transfer\":\"" + transferId + "\"}");
    }

    void payout(String account, String payoutId, long amount) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payouts"))
                .withHeader("Stripe-Account", WireMock.equalTo(account)),
                "{\"id\":\"" + payoutId + "\",\"object\":\"payout\",\"amount\":" + amount
                        + ",\"currency\":\"usd\",\"status\":\"pending\"}");
    }

    /** Platform balance transactions created in [gte, …). */
    void balanceTransactions(long createdGte, String dataJson) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/balance_transactions"))
                .withQueryParam("created[gte]", WireMock.equalTo(Long.toString(createdGte))), list(dataJson));
    }

    void customer(String afroCustomerId, String stripeCustomerId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/customers"))
                .withRequestBody(WireMock.containing(afroCustomerId)),
                "{\"id\":\"" + stripeCustomerId + "\",\"object\":\"customer\"}");
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/customers/" + stripeCustomerId)),
                "{\"id\":\"" + stripeCustomerId + "\",\"object\":\"customer\"}");
    }

    void ephemeralKey(String stripeCustomerId, String keyId, long expires) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/ephemeral_keys"))
                .withRequestBody(WireMock.containing("customer=" + stripeCustomerId)),
                "{\"id\":\"" + keyId + "\",\"object\":\"ephemeral_key\",\"secret\":\"ek_test_" + keyId
                        + "\",\"expires\":" + expires + ",\"associated_objects\":[{\"id\":\"" + stripeCustomerId
                        + "\",\"type\":\"customer\"}]}");
    }

    void setupIntent(String stripeCustomerId, String setupIntentId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/setup_intents"))
                .withRequestBody(WireMock.containing("customer=" + stripeCustomerId)),
                "{\"id\":\"" + setupIntentId + "\",\"object\":\"setup_intent\",\"client_secret\":\"" + setupIntentId
                        + "_secret_abc\",\"status\":\"requires_payment_method\"}");
    }

    /**
     * What Stripe holds for a Customer: {@code GET /v1/customers/{id}} with its default payment method and
     * {@code GET /v1/customers/{id}/payment_methods} with the attached methods (one page).
     */
    void customerWallet(String stripeCustomerId, String defaultPaymentMethodId, String... methodsJson) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/customers/" + stripeCustomerId)),
                "{\"id\":\"" + stripeCustomerId + "\",\"object\":\"customer\",\"invoice_settings\":"
                        + "{\"default_payment_method\":" + (defaultPaymentMethodId == null ? "null"
                        : "\"" + defaultPaymentMethodId + "\"") + "}}");
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/customers/" + stripeCustomerId + "/payment_methods")),
                "{\"object\":\"list\",\"url\":\"/v1/customers/" + stripeCustomerId + "/payment_methods\","
                        + "\"has_more\":false,\"data\":[" + String.join(",", methodsJson) + "]}");
    }

    /** Stripe cannot be reached for the Customer's payment methods. */
    void customerWalletDown(String stripeCustomerId) {
        server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/customers/" + stripeCustomerId + "/payment_methods"))
                .willReturn(WireMock.aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"api_error\",\"message\":\"Stripe is down\"}}")));
    }

    void paymentMethod(String pmJson, String pmId) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/payment_methods/" + pmId)), pmJson);
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_methods/" + pmId + "/detach")), pmJson);
    }

    static String intent(String piId, long amount, String status, String chargeId, long received) {
        return "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"amount\":" + amount
                + ",\"amount_capturable\":" + ("requires_capture".equals(status) ? amount - received : 0)
                + ",\"amount_received\":" + received + ",\"currency\":\"usd\",\"capture_method\":\"manual\","
                + "\"status\":\"" + status + "\",\"client_secret\":\"" + piId + "_secret_test\","
                + "\"latest_charge\":" + (chargeId == null ? "null" : "\"" + chargeId + "\"") + "}";
    }

    static String txn(String id, String type, String source, long amount, long fee, Instant created) {
        return "{\"id\":\"" + id + "\",\"object\":\"balance_transaction\",\"amount\":" + amount + ",\"fee\":" + fee
                + ",\"net\":" + (amount - fee) + ",\"currency\":\"usd\",\"type\":\"" + type + "\",\"source\":\""
                + source + "\",\"created\":" + created.getEpochSecond() + "}";
    }

    private static String list(String data) {
        return "{\"object\":\"list\",\"url\":\"/v1/balance_transactions\",\"has_more\":false,\"data\":[" + data
                + "]}";
    }
}
