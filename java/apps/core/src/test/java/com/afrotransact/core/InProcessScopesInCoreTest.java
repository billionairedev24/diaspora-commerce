package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.bookings.api.ReviewModerationApi;
import com.afrotransact.inventory.api.NodeAddressApi;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.api.SellerStandingApi;
import com.afrotransact.payments.api.BookingPaymentsApi;
import com.afrotransact.payments.api.ClaimRefundApi;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.seller.api.BillingPlanApi;
import com.afrotransact.seller.api.OrgMirrorApi;
import com.afrotransact.seller.api.SellerEnforcementApi;
import com.afrotransact.seller.api.ShipFromAddressApi;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * S6 (authorization parity) for the published APIs of the orders, payments, fulfilment, trust-ops, bookings and identity batch,
 * on the real application services of a running core (same environment as {@link CoreBootTest}): each method refuses a caller that
 * lacks the scope its HTTP endpoint needs with status 403, and does not refuse one that has it. The coupon calls and the
 * public reads ({@code ProductListingApi}, {@code MarketEligibilityApi}) check no scope over HTTP either, and
 * {@code CheckoutOffersApi.inventorySku} looks the offer up before it checks the scope (an unknown id is 404 first, as over
 * HTTP), so none of them has a case here. Orders and fulfilment need Valkey, Temporal and PostGIS and are off unless
 * {@code AT_TEST_CORE_FULL=true}; their {@code ShipmentApi} case is skipped then.
 */
@EnabledIfEnvironmentVariable(named = "AT_TEST_PG_ADMIN_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InProcessScopesInCoreTest {

    private static final boolean FULL = "true".equals(System.getenv("AT_TEST_CORE_FULL"));
    private static final Set<String> OFF = FULL ? Set.of() : Set.of("orders", "fulfilment", "ai-gateway", "mcp-server");

    private ConfigurableApplicationContext ctx;

    @BeforeAll
    void start() {
        String url = System.getenv("AT_TEST_PG_ADMIN_URL");
        Map<String, Object> properties = new HashMap<>(Map.of(
                "server.port", "0",
                "spring.profiles.active", "local",
                "at.consumers.enabled", "false",
                "CORE_DB_URL", url,
                "at.modules.schema-admin.url", url,
                "at.modules.schema-admin.username", System.getenv("AT_TEST_PG_ADMIN_USER"),
                "at.modules.schema-admin.password", System.getenv("AT_TEST_PG_ADMIN_PASSWORD")));
        for (String off : OFF) {
            properties.put("at.modules." + off + ".enabled", "false");
        }
        ctx = new SpringApplicationBuilder(CoreApplication.class)
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    @AfterAll
    void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    /** One published API method: the module it lives in, the scope its HTTP endpoint needs, and how to call it. */
    private record Method(String name, String module, String scope, Consumer<ConfigurableApplicationContext> call) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Arguments method(String name, String module, String scope, Consumer<ConfigurableApplicationContext> call) {
        return Arguments.of(new Method(name, module, scope, call));
    }

    private static final String PAYMENT = "6f1c1a3e-0000-4000-8000-000000000001";

    static Stream<Arguments> apiMethods() {
        OrderPaymentsApi.NewPayment payment = new OrderPaymentsApi.NewPayment("o-scope", 100L, "USD", Instant.now(), Map.of(),
                List.of(new OrderPaymentsApi.Line("l1", "org_x", "food", "SELF", 1, 100L, 0L, 0L, null, 0L, 0L)), null, List.of(),
                false, false, false, List.of());
        UUID id = UUID.fromString(PAYMENT);
        return Stream.of(
                method("ReservationApi.reserve", "inventory", "inventory:reserve", c -> c.getBean(ReservationApi.class)
                        .reserve("o-scope", "o-scope", List.of(new ReservationApi.Line("SKU-1", null, 1, "l1")))),
                method("ReservationApi.find", "inventory", "inventory:reserve",
                        c -> c.getBean(ReservationApi.class).find("o-scope")),
                method("ReservationApi.confirm", "inventory", "inventory:reserve",
                        c -> c.getBean(ReservationApi.class).confirm("o-scope")),
                method("ReservationApi.commit", "inventory", "inventory:reserve",
                        c -> c.getBean(ReservationApi.class).commit("o-scope")),
                method("ReservationApi.release", "inventory", "inventory:reserve",
                        c -> c.getBean(ReservationApi.class).release("o-scope", "test")),
                method("ReservationApi.commitGroups", "inventory", "inventory:reserve",
                        c -> c.getBean(ReservationApi.class).commitGroups("o-scope", Set.of("l1"))),
                method("ReservationApi.releaseGroups", "inventory", "inventory:reserve",
                        c -> c.getBean(ReservationApi.class).releaseGroups("o-scope", Set.of("l1"), "test")),
                method("NodeAddressApi.addressOf", "inventory", "inventory:read",
                        c -> c.getBean(NodeAddressApi.class).addressOf("FC1")),
                method("OrderPaymentsApi.create", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).create(payment, "o-scope")),
                method("OrderPaymentsApi.get", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).get(PAYMENT, false)),
                method("OrderPaymentsApi.findByOrder", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).findByOrder("o-scope")),
                method("OrderPaymentsApi.cancel", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).cancel(PAYMENT, "test", "k1")),
                method("OrderPaymentsApi.capture", "payments", "payments:service", c -> c.getBean(OrderPaymentsApi.class)
                        .capture(PAYMENT, new OrderPaymentsApi.CaptureRequest("s1", List.of("l1"), 100L, false), "k1")),
                method("OrderPaymentsApi.refund", "payments", "payments:service", c -> c.getBean(OrderPaymentsApi.class)
                        .refund(PAYMENT, "k1", List.of(new OrderPaymentsApi.RefundLine("l1", 1L, 0L, 0L)), "test")),
                method("OrderPaymentsApi.delivered", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).delivered("o-scope", Instant.now())),
                method("OrderPaymentsApi.shipmentDelivered", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).shipmentDelivered("o-scope", "s1", Instant.now())),
                method("OrderPaymentsApi.split", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).split("o-scope")),
                method("OrderPaymentsApi.quoteDelivery", "payments", "payments:service",
                        c -> c.getBean(OrderPaymentsApi.class).quoteDelivery("org_x", 3.5, 1)),
                method("MembershipBenefitsApi.earlyAccessHours", "payments", "payments:service",
                        c -> c.getBean(MembershipBenefitsApi.class).earlyAccessHours("cust-1")),
                method("MembershipBenefitsApi.quote", "payments", "payments:service", c -> c.getBean(MembershipBenefitsApi.class)
                        .quote("cust-1", 1000L, "USD", List.of(new MembershipBenefitsApi.Fee("s1", "CARRIER", 500L)))),
                method("BookingPaymentsApi.create", "payments", "payments:service", c -> c.getBean(BookingPaymentsApi.class)
                        .create(new BookingPaymentsApi.NewBookingPayment("b-scope", "org_x", 100L, "USD",
                                List.of(new BookingPaymentsApi.Line("l1", 100L)), Map.of(), Instant.now(), null, false,
                                "services"), "k1")),
                method("BookingPaymentsApi.get", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).get(id, false)),
                method("BookingPaymentsApi.capture", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).capture(id, "c1", List.of("l1"), 100L, false, "k1")),
                method("BookingPaymentsApi.cancel", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).cancel(id, "test", "k1")),
                method("BookingPaymentsApi.release", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).release(id, "test", "k1")),
                method("BookingPaymentsApi.chargeOffSession", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).chargeOffSession(id, "cust-1", null, List.of(), "k1")),
                method("BookingPaymentsApi.refund", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).refund(id, "l1", 100L, "test", "k1")),
                method("BookingPaymentsApi.serviceCompleted", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).serviceCompleted("b-scope", Instant.now())),
                method("BookingPaymentsApi.connectionFees", "payments", "payments:service",
                        c -> c.getBean(BookingPaymentsApi.class).connectionFees("services", "org_x")),
                method("ClaimRefundApi.refund", "payments", "payments:service", c -> c.getBean(ClaimRefundApi.class)
                        .refund(id, List.of(new ClaimRefundApi.RefundAmount("l1", 1L, 0L, 0L)), "test", "k1")),
                method("ClaimRefundApi.refundByOrder", "payments", "payments:service",
                        c -> c.getBean(ClaimRefundApi.class).refundByOrder("o-scope", List.of("l1"), 100L, "test", "k1")),
                method("TaxQuoteApi.quote", "tax-returns", "tax:quote", c -> c.getBean(TaxQuoteApi.class).quote(
                        new TaxQuoteApi.QuoteRequest("USD", new TaxQuoteApi.Place("US", "TX", "75001", "Addison", null),
                                List.of(new TaxQuoteApi.Line("l1", "AT00000000", "org_x", "food", 1, 100L, 0L, 0L, Map.of(),
                                        null)), "o-scope"))),
                method("SellerStandingApi.rating", "offers", "offers:read",
                        c -> c.getBean(SellerStandingApi.class).rating("org_x")),
                method("SellerStandingApi.updatePerformance", "offers", "offers:write",
                        c -> c.getBean(SellerStandingApi.class).updatePerformance("org_x", null, 0.9, 0.01, false)),
                method("ShipFromAddressApi.location", "seller", "sellers:ship-from.read",
                        c -> c.getBean(ShipFromAddressApi.class).location(UUID.randomUUID().toString())),
                method("ShipFromAddressApi.defaultLocation", "seller", "sellers:ship-from.read",
                        c -> c.getBean(ShipFromAddressApi.class).defaultLocation("org_x")),
                method("SellerEnforcementApi.reinstate", "seller", "sellers:enforce",
                        c -> c.getBean(SellerEnforcementApi.class).reinstate("org_unknown", "test")),
                method("BillingPlanApi.currentPlan", "seller", "seller:billing",
                        c -> c.getBean(BillingPlanApi.class).currentPlan("org_x")),
                method("OrgMirrorApi.page", "seller", "sellers:directory",
                        c -> c.getBean(OrgMirrorApi.class).page(0, 10, List.of("APPROVED"))),
                method("OrgMirrorApi.one", "seller", "sellers:directory", c -> c.getBean(OrgMirrorApi.class).one("org_x")),
                method("ReviewModerationApi.approve", "bookings", "bookings:reviews:moderate",
                        c -> c.getBean(ReviewModerationApi.class).approve(UUID.randomUUID(), "test")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiMethods")
    void aCallerWithoutTheScopeIsRefusedWith403AndOneWithItIsNot(Method m) {
        Assumptions.assumeTrue(!OFF.contains(m.module()), "module " + m.module() + " is not started in this run");
        CallerPrincipal without = new CallerPrincipal("scope-test", "scope-test-service", Set.of("unrelated:scope"));
        assertThatThrownBy(() -> InProcessCalls.as(without, () -> {
            m.call().accept(ctx);
            return null;
        })).as("%s without %s", m.name(), m.scope()).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).as("status of %s", m.name()).isEqualTo(403));
        CallerPrincipal with = new CallerPrincipal("scope-test", "scope-test-service", Set.of(m.scope()));
        try {
            InProcessCalls.as(with, () -> {
                m.call().accept(ctx);
                return null;
            });
        } catch (ModuleApiException e) {
            assertThat(e.status()).as("%s with %s must get past the scope check (got %s)", m.name(), m.scope(), e.getMessage())
                    .isNotEqualTo(403);
        } catch (RuntimeException e) {
            // any other outcome (a rule, an unknown id, a downstream refusal) means the scope check passed
        }
    }
}
