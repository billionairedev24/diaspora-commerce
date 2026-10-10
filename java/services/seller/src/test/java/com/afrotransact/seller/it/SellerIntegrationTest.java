package com.afrotransact.seller.it;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.ApplicationView;
import com.afrotransact.seller.application.OnboardingService;
import com.afrotransact.seller.application.ReviewCommand;
import com.afrotransact.seller.application.ReviewService;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.application.StepInput;
import com.afrotransact.seller.application.StripeOnboardingService;
import com.afrotransact.seller.domain.Address;
import com.afrotransact.seller.domain.BusinessDetailsInput;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.ComplianceSection;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Owner;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.PayoutInput;
import com.afrotransact.seller.domain.PlanSection;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One PostgreSQL container, one WireMock (Stripe + media) and one Spring context shared by every IT class. Tests use
 * unique users and organisations so they never interfere; jobs are off and called directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.seller.jobs.enabled=false",
    "at.seller.consumers.enabled=false",
    "at.seller.stripe.secret-key=sk_test_seller_it",
    "at.seller.stripe.connect-webhook-secret=" + SellerIntegrationTest.CONNECT_SECRET,
    "at.seller.stripe.billing-webhook-secret=" + SellerIntegrationTest.BILLING_SECRET,
    "at.seller.stripe.max-network-retries=0"
})
@ActiveProfiles("local")
@Import(TestActors.class)
abstract class SellerIntegrationTest {

    static final String CONNECT_SECRET = "whsec_connect_test_secret";
    static final String BILLING_SECRET = "whsec_billing_test_secret";
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer STRIPE = new WireMockServer(WireMockConfiguration.options().dynamicPort()
            .globalTemplating(true));
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Address ADDRESS = new Address("9800 Westheimer Rd", null, "Houston", "TX", "77042", "US");

    static {
        POSTGRES.start();
        STRIPE.start();
        StripeStub.install(STRIPE);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("at.seller.stripe.api-base", STRIPE::baseUrl);
        registry.add("at.seller.stripe.files-base", STRIPE::baseUrl);
        registry.add("at.seller.media.base-url", STRIPE::baseUrl);
        registry.add("at.seller.geo.base-url", STRIPE::baseUrl);
        registry.add("at.seller.metering.inventory-url", STRIPE::baseUrl);
        registry.add("at.seller.metering.content-url", STRIPE::baseUrl);
        registry.add("at.seller.metering.ai-url", STRIPE::baseUrl);
    }

    JdbcClient jdbc;
    OnboardingService onboarding;
    StripeOnboardingService stripeOnboarding;
    ReviewService reviews;
    int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @Autowired OnboardingService onboardingService,
                  @Autowired StripeOnboardingService stripeOnboardingService, @Autowired ReviewService reviewService,
                  @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.onboarding = onboardingService;
        this.stripeOnboarding = stripeOnboardingService;
        this.reviews = reviewService;
        this.port = serverPort;
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    long events(String type, String aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :t and aggregate_id = :id")
                .param("t", type).param("id", aggregateId).query(Long.class).single();
    }

    JsonNode lastEvent(String type, String aggregateId) {
        String payload = jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                + "order by occurred_at desc limit 1").param("t", type).param("id", aggregateId)
                .query(String.class).single();
        return JSON.readTree(payload);
    }

    /** Drives an application through onboarding and both review steps; returns the approved org id. */
    String approvedSeller(String user, String planCode, Cadence cadence) {
        return approvedSeller(user, planCode, cadence, Offering.PRODUCTS);
    }

    /** Same, offering products or products and services (S215: BOTH). */
    String approvedSeller(String user, String planCode, Cadence cadence, Offering offering) {
        TestActors.as(TestActors.applicant(user));
        onboarding.start(OrgKind.SELLER);
        onboarding.saveStep(OnboardingStep.BUSINESS, StepInput.business(new BusinessSection(offering,
                EntityType.SINGLE_MEMBER_LLC, "US", "Store " + user, user + "@example.com", "+17132490142", null)));
        onboarding.saveStep(OnboardingStep.BUSINESS_DETAILS, StepInput.businessDetails(new BusinessDetailsInput(null,
                null, "Store " + user + " LLC", null, "12-3456789", null, "TX", LocalDate.of(2020, 1, 1), ADDRESS,
                "asset-formation", null)));
        onboarding.saveStep(OnboardingStep.OWNERS, StepInput.owners(List.of(new Owner("Ade", "Ade", "CEO", null,
                BigDecimal.valueOf(100), true, LocalDate.of(1981, 1, 1)))));
        stripeOnboarding.accountSession();
        stripeOnboarding.refreshVerification();
        onboarding.saveStep(OnboardingStep.PAYOUTS, StepInput.payouts(new PayoutInput("Store LLC", "checking",
                "Chase", "021000021", "000123456789", "000123456789", null)));
        onboarding.saveStep(OnboardingStep.PLAN, StepInput.plan(new PlanSection(planCode, cadence,
                PaymentMethodKind.CARD, "pm_card_" + user.replace("-", ""), "visa", "4242", 12, 2030)));
        List<ComplianceSection.ServiceLicence> services = offering.includesServices()
                ? List.of(new ComplianceSection.ServiceLicence("Gele tying", false, null, null, null, null))
                : List.of();
        onboarding.saveStep(OnboardingStep.CATALOG_COMPLIANCE, StepInput.compliance(new ComplianceSection(
                List.of("fashion"), null, null, services, null, true, true, "Ade")));
        ApplicationView submitted = onboarding.submit();
        String appId = submitted.application().id();
        TestActors.as(TestActors.staff("reviewer-" + user, SellerPermissions.APPLICATIONS_REVIEW));
        reviews.review(appId, new ReviewCommand(ReviewAction.APPROVE, "ALL_CHECKS_PASSED", null, null, null));
        TestActors.as(TestActors.staff("approver-" + user, SellerPermissions.APPLICATIONS_APPROVE));
        reviews.decide(appId, new ReviewCommand(ReviewAction.FINAL_APPROVE, "ALL_CHECKS_PASSED", null, null, null));
        TestActors.reset();
        return submitted.application().orgId();
    }

    String accountOf(String orgId) {
        return jdbc.sql("select account_id from connect_account where org_id = :o").param("o", orgId)
                .query(String.class).single();
    }

    /** Signs {@code payload} like Stripe does: {@code t=<ts>,v1=HMAC-SHA256(secret, ts + "." + payload)}. */
    static String sign(String payload, String secret) {
        long ts = System.currentTimeMillis() / 1000;
        try {
            return "t=" + ts + ",v1=" + Webhook.Util.computeHmacSha256(secret, ts + "." + payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    Response post(String path, Object body) {
        return send("POST", path, body, null);
    }

    Response put(String path, Object body) {
        return send("PUT", path, body, null);
    }

    Response get(String path) {
        return send("GET", path, null, null);
    }

    Response delete(String path) {
        return send("DELETE", path, null, null);
    }

    Response webhook(String path, String payload, String signature) {
        return send("POST", path, payload, signature);
    }

    private Response send(String method, String path, Object body, String signature) {
        try {
            String raw = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8));
            if (signature != null) {
                b.header("Stripe-Signature", signature);
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
            return new Response(r.statusCode(), json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** HTTP status and JSON body. */
    record Response(int status, JsonNode body) {

        String text(String pointer) {
            JsonNode n = body.at(pointer);
            return n.isMissingNode() || n.isNull() ? null : n.asString();
        }
    }
}
