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
        wm.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/prices"))
                .willReturn(json("{\"object\":\"list\",\"data\":[{\"id\":\"price_" + RANDOM + "\",\"object\":"
                        + "\"price\",\"active\":true}],\"has_more\":false,\"url\":\"/v1/prices\"}")));
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/subscriptions"))
                .willReturn(json(subscription("sub_" + RANDOM, "trialing"))));
        wm.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/subscriptions/sub_[A-Za-z0-9]+"))
                .willReturn(json(subscription("{{request.pathSegments.[2]}}", "active"))));
        wm.stubFor(WireMock.delete(WireMock.urlPathMatching("/v1/subscriptions/sub_[A-Za-z0-9]+"))
                .willReturn(json(subscription("{{request.pathSegments.[2]}}", "canceled"))));
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
