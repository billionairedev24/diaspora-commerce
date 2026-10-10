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

    void setupIntent(String stripeCustomerId, String setupIntentId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/setup_intents"))
                .withRequestBody(WireMock.containing("customer=" + stripeCustomerId)),
                "{\"id\":\"" + setupIntentId + "\",\"object\":\"setup_intent\",\"client_secret\":\"" + setupIntentId
                        + "_secret_abc\",\"status\":\"requires_payment_method\"}");
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
