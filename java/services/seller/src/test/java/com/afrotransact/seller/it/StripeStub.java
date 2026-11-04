package com.afrotransact.seller.it;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * In-JVM WireMock standing in for api.stripe.com and media-service. Responses are templated so every organisation
 * gets distinct Stripe ids. Seller accounts are Stripe-collected, so there are no stubs for account, person or bank
 * account updates: a call to one fails the test. Retrieved accounts list the bank Stripe's onboarding collected.
 */
final class StripeStub {

    static final String RANDOM = "{{randomValue length=14 type='ALPHANUMERIC'}}";

    private StripeStub() {
    }

    static void install(WireMockServer wm) {
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/accounts"))
                .willReturn(json("{\"id\":\"acct_" + RANDOM + "\",\"object\":\"account\",\"charges_enabled\":false,"
                        + "\"payouts_enabled\":false,\"details_submitted\":false}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/accounts/acct_[A-Za-z0-9]+"))
                .willReturn(json(account("{{request.pathSegments.[2]}}"))));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/account_sessions"))
                .willReturn(json("{\"object\":\"account_session\",\"account\":\"acct_x\",\"client_secret\":"
                        + "\"accs_secret_test\",\"expires_at\":4102444800,\"livemode\":false}")));
        // Plan fees are plain Stripe Payments: a customer per organisation, SetupIntents, off-session PaymentIntents.
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/customers"))
                .willReturn(json("{\"id\":\"cus_" + RANDOM + "\",\"object\":\"customer\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/payment_methods/pm_[A-Za-z0-9_]+/(attach|detach)"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"object\":\"payment_method\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/setup_intents"))
                .willReturn(json("{\"id\":\"seti_" + RANDOM + "\",\"object\":\"setup_intent\",\"client_secret\":"
                        + "\"seti_" + RANDOM + "_secret_test\",\"status\":\"requires_payment_method\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .willReturn(json("{\"id\":\"pi_" + RANDOM + "\",\"object\":\"payment_intent\",\"status\":"
                        + "\"succeeded\"}")));
        // Bank debits settle later: the payment method id says so.
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("payment_method=pm_ach")).atPriority(1)
                .willReturn(json("{\"id\":\"pi_" + RANDOM + "\",\"object\":\"payment_intent\",\"status\":"
                        + "\"processing\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("payment_method=pm_decline")).atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(402).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\","
                                + "\"decline_code\":\"generic_decline\",\"message\":\"Your card was declined.\","
                                + "\"payment_intent\":{\"id\":\"pi_declined\",\"object\":\"payment_intent\"}}}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/assets/[A-Za-z0-9-]+"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"fileName\":\"document.pdf\","
                        + "\"contentType\":\"application/pdf\",\"status\":\"READY\","
                        + "\"url\":\"{{request.baseUrl}}/blobs/{{request.pathSegments.[2]}}\"}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/blobs/.*"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/pdf")
                        .withBody("%PDF-1.4 test document")));
    }

    static String account(String id) {
        return "{\"id\":\"" + id + "\",\"object\":\"account\",\"business_type\":\"individual\","
                + "\"charges_enabled\":true,\"payouts_enabled\":true,\"details_submitted\":true,"
                + "\"individual\":{\"verification\":{\"status\":\"verified\"}},"
                + "\"capabilities\":{\"card_payments\":\"active\",\"transfers\":\"active\"},"
                + "\"requirements\":{\"currently_due\":[],\"eventually_due\":[],\"past_due\":[],"
                + "\"pending_verification\":[],\"errors\":[],\"current_deadline\":null,\"disabled_reason\":null},"
                + "\"external_accounts\":{\"object\":\"list\",\"data\":[{\"id\":\"ba_" + id + "\","
                + "\"object\":\"bank_account\",\"bank_name\":\"STRIPE TEST BANK\",\"last4\":\"6789\","
                + "\"status\":\"new\",\"default_for_currency\":true,\"currency\":\"usd\"}],\"has_more\":false}}";
    }

    private static ResponseDefinitionBuilder json(String body) {
        return WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Form-decoded bodies of the requests to {@code path} (exact path). */
    static List<String> bodies(WireMockServer wm, String method, String path) {
        return wm.getAllServeEvents().stream().map(e -> e.getRequest())
                .filter(r -> r.getMethod().getName().equals(method) && r.getUrl().split("\\?")[0].equals(path))
                .map(StripeStub::decoded).toList();
    }

    static List<LoggedRequest> requests(WireMockServer wm, String method, String path) {
        return wm.getAllServeEvents().stream().map(e -> e.getRequest())
                .filter(r -> r.getMethod().getName().equals(method) && r.getUrl().split("\\?")[0].equals(path))
                .toList();
    }

    static String decoded(LoggedRequest r) {
        return URLDecoder.decode(r.getBodyAsString(), StandardCharsets.UTF_8);
    }
}
