package com.afrotransact.seller.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.seller.config.SellerProperties;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guardrails of the sellers' Stripe accounts. Sellers never see a Stripe dashboard and never get paid on Stripe's
 * schedule: payments-service releases payouts after delivery, and sellers see their balance and payouts in Seller
 * Center. If connected accounts were created with a dashboard (a seller could then change the payout schedule or
 * debit their own balance in Stripe) or with an automatic payout schedule (Stripe would pay out every transfer
 * before a refund could reverse it, which is how reversals end up refused), or if an Account Session enabled a payouts
 * component, these tests fail.
 *
 * <p>They exercise the real adapter against a Stripe stand-in and read what it sent, so a change to the parameters
 * is caught wherever it is made.
 */
class StripeConnectGuardrailsTest {

    /** The only embedded components sellers get. */
    private static final Set<String> ALLOWED_COMPONENTS = Set.of("account_onboarding", "account_management",
            "notification_banner");
    /** Stripe's other embedded components, including every one that shows or moves payouts or the balance. */
    private static final Set<String> OTHER_STRIPE_COMPONENTS = Set.of("payouts", "payouts_list", "payout_details",
            "balances", "payments", "payment_details", "disputes_list", "documents", "tax_registrations",
            "tax_settings", "instant_payouts_promotion", "account_onboarding_extra");
    private static final Pattern COMPONENT = Pattern.compile("components\\[([a-z_]+)]");

    private static WireMockServer stripe;
    private StripeConnectAdapter adapter;

    @BeforeAll
    static void startStripe() {
        stripe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripe.start();
    }

    @AfterAll
    static void stopStripe() {
        stripe.stop();
    }

    @BeforeEach
    void wire() {
        stripe.resetAll();
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/accounts")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"acct_guard\",\"object\":\"account\"}")));
        stripe.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/account_sessions")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"account_session\",\"account\":\"acct_guard\",\"client_secret\":\"accs_x\","
                        + "\"expires_at\":4102444800,\"livemode\":false}")));
        StripeClient client = StripeClient.builder().setApiKey("sk_test_guard").setApiBase(stripe.baseUrl())
                .setConnectBase(stripe.baseUrl()).setMaxNetworkRetries(0).build();
        adapter = new StripeConnectAdapter(client, JsonMapper.builder().build());
    }

    private static String decoded(LoggedRequest request) {
        return URLDecoder.decode(request.getBodyAsString(), StandardCharsets.UTF_8);
    }

    private static List<String> params(String formBody) {
        return List.of(formBody.split("&"));
    }

    private String createAccount(String businessType) {
        adapter.createAccount(new CreateConnectedAccount("org_guard", "app_guard", "US", "owner@example.com",
                businessType, "connect-account-guard", "https://shop.example.com", "Groceries"));
        List<LoggedRequest> sent = stripe.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/accounts")));
        assertThat(sent).hasSize(1);
        return decoded(sent.getFirst());
    }

    @Test
    void connectedAccountsHaveNoStripeDashboard() {
        for (String businessType : List.of("individual", "company", "non_profit")) {
            stripe.resetRequests();
            List<String> body = params(createAccount(businessType));
            assertThat(body).as("controller[stripe_dashboard][type] for " + businessType)
                    .contains("controller[stripe_dashboard][type]=none");
            assertThat(body.stream().filter(p -> p.startsWith("controller[stripe_dashboard]")).toList())
                    .as("nothing else is said about a dashboard").containsExactly(
                            "controller[stripe_dashboard][type]=none");
            assertThat(body).as("the legacy account type would bring a dashboard").noneMatch(p -> p.startsWith("type="));
        }
    }

    @Test
    void connectedAccountsPayOutOnlyWhenPaymentsServiceAsks() {
        List<String> body = params(createAccount("individual"));
        assertThat(body).contains("settings[payouts][schedule][interval]=manual");
        assertThat(body.stream().filter(p -> p.startsWith("settings[payouts]")).toList())
                .as("no delay, weekly or monthly anchor that would pay out on a schedule")
                .containsExactly("settings[payouts][schedule][interval]=manual");
        assertThat(body).noneMatch(p -> p.matches("settings\\[payouts]\\[schedule]\\[interval]=(daily|weekly|monthly)"));
    }

    @Test
    void accountSessionsNeverEnableAPayoutsComponent() {
        Set<String> everything = new LinkedHashSet<>(ALLOWED_COMPONENTS);
        everything.addAll(OTHER_STRIPE_COMPONENTS);
        adapter.createAccountSession("acct_guard", everything);

        List<LoggedRequest> sent = stripe.findAll(WireMock.postRequestedFor(
                WireMock.urlPathEqualTo("/v1/account_sessions")));
        assertThat(sent).hasSize(1);
        String body = decoded(sent.getFirst());
        Set<String> enabled = new LinkedHashSet<>();
        Matcher m = COMPONENT.matcher(body);
        while (m.find()) {
            enabled.add(m.group(1));
        }
        assertThat(enabled).as("components enabled in the Account Session, whatever is asked for")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_COMPONENTS);
        assertThat(body).doesNotContain("payout").doesNotContain("balances").doesNotContain("instant_payouts");
        assertThat(params(body).stream().filter(p -> p.endsWith("[enabled]=true")).count())
                .isEqualTo(ALLOWED_COMPONENTS.size());
    }

    @Test
    void theDefaultSessionComponentsAreTheOnesSellersMayHave() {
        SellerProperties.Stripe defaults = new SellerProperties.Stripe("sk", null, null, null, null, null, null,
                null);
        assertThat(defaults.sessionComponents()).containsExactlyInAnyOrderElementsOf(ALLOWED_COMPONENTS);
    }
}
