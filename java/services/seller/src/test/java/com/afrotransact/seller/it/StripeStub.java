package com.afrotransact.seller.it;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * In-JVM WireMock standing in for api.stripe.com, files.stripe.com and media-service. Responses are templated so
 * every organisation gets distinct Stripe ids.
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
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/accounts/acct_[A-Za-z0-9]+"))
                .willReturn(json(account("{{request.pathSegments.[2]}}"))));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/accounts/acct_[A-Za-z0-9]+/external_accounts"))
                .willReturn(json("{\"id\":\"ba_" + RANDOM + "\",\"object\":\"bank_account\",\"last4\":\"6789\","
                        + "\"bank_name\":\"Chase\",\"status\":\"new\"}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/accounts/acct_[A-Za-z0-9]+/persons"))
                .willReturn(json("{\"object\":\"list\",\"data\":[{\"id\":\"person_Rep1\",\"object\":\"person\","
                        + "\"relationship\":{\"representative\":true}}],\"has_more\":false,\"url\":\"/v1/persons\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/accounts/acct_[A-Za-z0-9]+/persons/person_[A-Za-z0-9]+"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[4]}}\",\"object\":\"person\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/account_sessions"))
                .willReturn(json("{\"object\":\"account_session\",\"account\":\"acct_x\",\"client_secret\":"
                        + "\"accs_secret_test\",\"expires_at\":4102444800,\"livemode\":false}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/files"))
                .willReturn(json("{\"id\":\"file_" + RANDOM + "\",\"object\":\"file\",\"purpose\":"
                        + "\"account_requirement\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/customers"))
                .willReturn(json("{\"id\":\"cus_" + RANDOM + "\",\"object\":\"customer\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/customers/cus_[A-Za-z0-9]+"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"object\":\"customer\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/payment_methods/pm_[A-Za-z0-9_]+/(attach|detach)"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"object\":\"payment_method\"}")));
        // Plan catalogue sync: no Price under a lookup key unless a test stubs one; created Prices echo the form.
        wm.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/prices"))
                .willReturn(json("{\"object\":\"list\",\"data\":[],\"has_more\":false,\"url\":\"/v1/prices\"}")));
        // Metered usage prices (S206) are still looked up by their metered_* lookup keys.
        wm.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/prices"))
                .withQueryParam("lookup_keys[0]", WireMock.matching("metered_.*")).atPriority(1)
                .willReturn(json("{\"object\":\"list\",\"data\":[{\"id\":\"price_" + RANDOM + "\",\"object\":"
                        + "\"price\",\"active\":true}],\"has_more\":false,\"url\":\"/v1/prices\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/prices"))
                .willReturn(json("{{formData request.body 'f' urlDecode=true}}{\"id\":\"price_" + RANDOM + "\","
                        + "\"object\":\"price\",\"active\":true,\"currency\":\"usd\",\"product\":\"{{f.product}}\","
                        + "\"unit_amount\":{{f.unit_amount}},\"lookup_key\":\"{{f.lookup_key}}\","
                        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}")));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/prices/price_[A-Za-z0-9_]+"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"object\":\"price\",\"active\":false}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/prices/price_[A-Za-z0-9_]+"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\","
                                + "\"message\":\"No such price\"}}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/products"))
                .willReturn(json("{\"id\":\"prod_" + RANDOM + "\",\"object\":\"product\",\"active\":true}")));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/products/prod_[A-Za-z0-9_]+"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"object\":\"product\"}")));
        // Credits, coupons, discounts, waived invoices.
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/customers/cus_[A-Za-z0-9]+/balance_transactions"))
                .willReturn(json("{{formData request.body 'f' urlDecode=true}}{\"id\":\"cbtxn_" + RANDOM + "\","
                        + "\"object\":\"customer_balance_transaction\",\"amount\":{{f.amount}},"
                        + "\"ending_balance\":{{f.amount}},\"currency\":\"usd\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/coupons"))
                .willReturn(json("{{formData request.body 'f' urlDecode=true}}{\"id\":\"{{f.id}}\",\"object\":\"coupon\"}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/subscriptions/sub_[A-Za-z0-9]+"))
                .willReturn(json(subscriptionWithDiscount("{{request.pathSegments.[2]}}"))));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/invoices/in_[A-Za-z0-9_]+/void"))
                .willReturn(json("{\"id\":\"{{request.pathSegments.[2]}}\",\"object\":\"invoice\",\"status\":\"void\","
                        + "\"amount_due\":0,\"amount_paid\":0,\"currency\":\"usd\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/subscriptions"))
                .willReturn(json(subscription("sub_" + RANDOM, "trialing"))));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/subscriptions/sub_[A-Za-z0-9]+"))
                .willReturn(json(subscription("{{request.pathSegments.[2]}}", "active"))));
        wm.stubFor(WireMock.delete(WireMock.urlPathMatching("/v1/subscriptions/sub_[A-Za-z0-9]+"))
                .willReturn(json(subscription("{{request.pathSegments.[2]}}", "canceled"))));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/billing/meter_events"))
                .willReturn(json("{\"object\":\"billing.meter_event\",\"event_name\":\"x\","
                        + "\"identifier\":\"x\",\"payload\":{},\"timestamp\":0,\"livemode\":false}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/setup_intents"))
                .willReturn(json("{\"id\":\"seti_" + RANDOM + "\",\"object\":\"setup_intent\",\"client_secret\":"
                        + "\"seti_" + RANDOM + "_secret_test\",\"status\":\"requires_payment_method\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/invoices/create_preview"))
                .willReturn(json("{\"object\":\"invoice\",\"amount_due\":1734,\"total\":1734,"
                        + "\"currency\":\"usd\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/subscription_items"))
                .willReturn(json("{\"id\":\"si_" + RANDOM + "\",\"object\":\"subscription_item\"}")));
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
                + "\"pending_verification\":[],\"errors\":[],\"current_deadline\":null,\"disabled_reason\":null}}";
    }

    private static String subscription(String id, String status) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"customer\":\"cus_x\",\"status\":\"" + status
                + "\",\"cancel_at_period_end\":false,\"trial_end\":" + (now + 30L * 86400) + ",\"items\":{\"object\":"
                + "\"list\",\"data\":[{\"id\":\"si_" + RANDOM + "\",\"object\":\"subscription_item\","
                + "\"current_period_start\":" + now + ",\"current_period_end\":" + (now + 30L * 86400)
                + ",\"price\":{\"id\":\"price_x\",\"object\":\"price\"}}]}}";
    }

    /** A trialing subscription that already has one discount (so adding a coupon must keep it). */
    private static String subscriptionWithDiscount(String id) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"customer\":\"cus_x\",\"status\":\"trialing\","
                + "\"discounts\":[\"di_existing\"],\"cancel_at_period_end\":false,\"trial_end\":" + (now + 30L * 86400)
                + ",\"items\":{\"object\":\"list\",\"data\":[]}}";
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
