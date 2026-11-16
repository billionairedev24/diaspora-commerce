package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.afrotransact.catalog.api.ProductListingApi;
import com.afrotransact.content.api.MarketEligibilityApi;
import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.identity.api.AddressBookApi;
import com.afrotransact.identity.api.StaffDirectoryApi;
import com.afrotransact.inventory.api.NodeAddressApi;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleCallTimeoutException;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.adapter.out.http.HttpWiring;
import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.OfferInventory;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.ShipFromAddresses;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.platform.security.EndUsers;
import com.afrotransact.seller.api.ShipFromAddressApi;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Release 1 turned the HTTP adapters of orders into in-process ones behind the same ports; the saga must not notice. For every
 * adapter orders' checkout path uses and every operation of it, this runs the SAME callee failure through both: the HTTP adapter
 * against a real local HTTP server that answers the status and RFC 9457 problem a callee advice would, and the in-process adapter
 * over a callee API that throws the {@link ModuleApiException} the callee's failure mapping gives for that status. The outcomes
 * must be equal: the same result (the degraded answer of the best-effort adapters) or the same {@link UpstreamFailure} (service,
 * status, code, retryability, shortfall lines, message), which is everything {@code CheckoutActivitiesImpl.guard} and the
 * compensations look at.
 *
 * <p>Callee failures covered: 400 validation, 403 forbidden, 404 not found, 408, 409 conflict, 409 insufficient stock (with its
 * shortfalls), 409 reservation expired, 422 rule, 422 coupon not applicable, 429, 500, 502 stripe, 503, and the callee crashing
 * or timing out (HTTP: a read timeout; in-process: an unexpected exception).
 */
class InProcessVersusHttpFailureParityTest {

    private static final Pattern SHORTFALL = Pattern.compile("(.+): requested (\\d+), available (\\d+)");
    private static final Duration TIMEOUT = Duration.ofMillis(400);
    private static final OrdersSettings SETTINGS = new OrdersSettings("org-retail", Duration.ofMinutes(15),
            Duration.ofSeconds(5), Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");

    /** What the scripted callee answers now. */
    private static volatile Callee callee;
    private static HttpServer server;
    private static String url;

    /** One callee failure: the HTTP answer, and the exception the called module's failure mapping throws for it. */
    record Callee(String name, int status, String code, String detail, List<String> shortfalls, boolean crash) {

        @Override
        public String toString() {
            return name;
        }

        RuntimeException thrown() {
            if (name.startsWith("bounded wait")) {
                return new ModuleCallTimeoutException("orders", "callee", TIMEOUT);
            }
            return crash ? new IllegalStateException("could not execute statement; password=hunter2")
                    : new ModuleApiException(status, code, detail, shortfalls, null);
        }
    }

    /** One operation of one adapter, run through a client built by the supplied factory. */
    record Op(String adapter, String name, Function<Wiring, Object> call) {

        @Override
        public String toString() {
            return adapter + "." + name;
        }
    }

    /** Both ways of reaching the same callee failure. */
    record Wiring(boolean http, String url, Supplier<RuntimeException> fault) {

        <A> A api(Class<A> type) {
            InvocationHandler fail = (proxy, method, args) -> {
                throw fault.get();
            };
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, fail));
        }
    }

    @BeforeAll
    static void startCallee() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Callee c = callee;
            try {
                exchange.getRequestBody().readAllBytes();
                if (c.crash()) {
                    Thread.sleep(TIMEOUT.toMillis() * 4);
                }
                StringBuilder body = new StringBuilder("{\"type\":\"about:blank\",\"status\":" + c.status() + ",\"code\":\""
                        + c.code() + "\",\"detail\":\"" + c.detail() + "\"");
                if (!c.shortfalls().isEmpty()) {
                    body.append(",\"shortfalls\":[");
                    List<String> items = new ArrayList<>();
                    for (String s : c.shortfalls()) {
                        Matcher n = SHORTFALL.matcher(s);
                        assertThat(n.matches()).isTrue();
                        items.add("{\"sku\":\"" + n.group(1) + "\",\"requested\":" + n.group(2) + ",\"available\":"
                                + n.group(3) + "}");
                    }
                    body.append(String.join(",", items)).append(']');
                }
                body.append('}');
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                exchange.sendResponseHeaders(c.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException | InterruptedException e) {
                // the client gave up (the timeout case)
            } finally {
                exchange.close();
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopCallee() {
        server.stop(0);
    }

    static Stream<Callee> failures() {
        return Stream.of(
                new Callee("400 validation", 400, "x.invalid-input", "Invalid input", List.of(), false),
                new Callee("403 forbidden", 403, "auth.missing-permission", "Missing permission", List.of(), false),
                new Callee("404 not found", 404, "x.not-found", "Not found", List.of(), false),
                new Callee("408 request timeout", 408, "x.timeout", "Timed out", List.of(), false),
                new Callee("409 conflict", 409, "x.invalid-state", "Wrong state", List.of(), false),
                new Callee("409 insufficient stock", 409, "inventory.insufficient-stock", "Insufficient stock",
                        List.of("SKU1: requested 5, available 1", "SKU2: requested 2, available 0"), false),
                new Callee("409 reservation expired", 409, "inventory.reservation-expired", "The hold expired",
                        List.of(), false),
                new Callee("422 rule violation", 422, "x.rule", "Rule broken", List.of(), false),
                new Callee("422 coupon not applicable", 422, "offers.coupon-not-applicable", "Coupon expired",
                        List.of(), false),
                new Callee("429 rate limited", 429, "x.rate-limited", "Slow down", List.of(), false),
                new Callee("500 server error", 500, "x.boom", "Something broke", List.of(), false),
                new Callee("502 stripe unavailable", 502, "payments.stripe-unavailable", "Stripe could not be reached",
                        List.of(), false),
                new Callee("503 unavailable", 503, "x.unavailable", "Try later", List.of(), false),
                // the callee module's bulkhead is full: the gate answers 503 <callee>.unavailable at once, as a 503 over HTTP
                new Callee("bulkhead full", 503, "callee.unavailable", "callee is busy: too many calls in flight", List.of(),
                        false),
                new Callee("crash or timeout", 500, "x.crash", "n/a", List.of(), true),
                // the bounded wait of the in-process gate fires: over HTTP that is the read timeout (the server never answers)
                new Callee("bounded wait timeout", 500, "x.timeout", "n/a", List.of(), true));
    }

    private static final String OFFER = "11111111-1111-1111-1111-111111111111";

    static Stream<Op> operations() {
        List<InventoryClient.Item> items = List.of(new InventoryClient.Item("SKU1", 5, "l1"));
        PaymentsClient.NewIntent intent = new PaymentsClient.NewIntent("o1", 1000, "USD", Instant.EPOCH, Map.of(), List.of(),
                "c1");
        CouponsClient.Request coupon = new CouponsClient.Request("WELCOME10", "c1", "o1", "USD",
                List.of(new CouponsClient.Line(OFFER, 1)), 0, false);
        TaxQuoteClient.Request tax = new TaxQuoteClient.Request("USD", "US", "TX", "77002", "Houston", "1 Main St",
                List.of(), "o1");
        return Stream.of(
                new Op("inventory", "reserve", w -> inventory(w).reserve("o1", items)),
                new Op("inventory", "confirm", w -> run(() -> inventory(w).confirm("o1"))),
                new Op("inventory", "commit", w -> run(() -> inventory(w).commit("o1"))),
                new Op("inventory", "release", w -> run(() -> inventory(w).release("o1", "r"))),
                new Op("inventory", "commitGroups", w -> run(() -> inventory(w).commitGroups("o1", "s1", List.of("l1")))),
                new Op("inventory", "releaseGroups",
                        w -> run(() -> inventory(w).releaseGroups("o1", "s1", List.of("l1"), "r"))),
                new Op("payments", "createIntent", w -> payments(w).createIntent(intent)),
                new Op("payments", "get", w -> payments(w).get("p1", false)),
                new Op("payments", "findByOrder", w -> payments(w).findByOrder("o1")),
                new Op("payments", "cancel", w -> run(() -> payments(w).cancel("p1", "r"))),
                new Op("payments", "capture", w -> payments(w).capture("p1",
                        new PaymentsClient.CaptureRequest("s1", List.of("l1"), 1000, true))),
                new Op("payments", "refund", w -> payments(w).refund("p1", "k", List.of(), "r")),
                new Op("payments", "delivered", w -> run(() -> payments(w).delivered("o1", Instant.EPOCH))),
                new Op("payments", "split", w -> payments(w).split("o1")),
                new Op("payments", "quoteDelivery", w -> payments(w).quoteDelivery("org", 3.0, 1)),
                new Op("coupons", "validate", w -> coupons(w).validate(coupon)),
                new Op("coupons", "redeem", w -> coupons(w).redeem("o1", coupon)),
                new Op("coupons", "release", w -> coupons(w).release("red-1")),
                new Op("tax", "quote", w -> tax(w).quote(tax)),
                new Op("offerInventory", "inventorySku", w -> offerInventory(w).inventorySku(OFFER)),
                new Op("productFacts", "facts", w -> facts(w).facts(List.of("A1"))),
                new Op("productFacts", "listings", w -> facts(w).listings(List.of("A1"))),
                new Op("markets", "check", w -> markets(w).check("US", "TX", "77002")),
                new Op("memberBenefits", "earlyAccessHours", w -> benefits(w).earlyAccessHours("c1")),
                new Op("memberBenefits", "waivers", w -> benefits(w).waivers("c1", 1000, "USD",
                        List.of(new MemberBenefits.Fee("1", MemberBenefits.FeeKind.PICKUP, 599)))),
                new Op("pickupLocations", "forOffer", w -> pickups(w).forOffer(OFFER)),
                new Op("addressBook", "find", w -> signedIn(() -> addressBook(w).find("customer-token", "addr-1"))),
                new Op("runSlots", "openRuns", w -> runSlots(w).openRuns("78602")),
                new Op("runSlots", "reserve", w -> run(() -> runSlots(w).reserve("run-1", "o1", "c1", 24,
                        new DeliveryRunSlots.RunCharge(799, 2500, "DOOR")))),
                new Op("runSlots", "release", w -> run(() -> runSlots(w).release("run-1", "o1"))),
                new Op("preorders", "eligibility", w -> preorders(w).eligibility("US", "TX", "Lubbock", List.of("A1"), 0)),
                new Op("deliveryFeeRules", "carrier", w -> feeRules(w).carrier("USD", 900L, 5000)),
                new Op("staffDirectory", "withPermission", w -> staff(w).withPermission("orders:review")),
                new Op("staffDirectory", "withRole", w -> staff(w).withRole("staff.support")),
                new Op("staffDirectory", "people", w -> staff(w).people(List.of("u1"))),
                new Op("shipFromAddresses", "centre", w -> shipFrom(w).forShipment(FulfilmentType.FBA_AFROTRANSACT, "org",
                        "node-1", null)),
                new Op("shipFromAddresses", "pickupLocation", w -> shipFrom(w).forShipment(FulfilmentType.SELLER_FULFILLED,
                        "org", null, "loc-1")));
    }

    static Stream<Arguments> matrix() {
        List<Op> ops = operations().toList();
        return failures().flatMap(f -> ops.stream().map(o -> Arguments.of(f, o)));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    void aCalleeFailureIsTheSameOutcomeInProcessAndOverHttp(Callee failure, Op op) {
        callee = failure;
        Object overHttp = outcome(() -> op.call().apply(new Wiring(true, url, failure::thrown)));
        Object inProcess = outcome(() -> op.call().apply(new Wiring(false, url, failure::thrown)));
        assertThat(inProcess).as("in-process outcome of %s when the callee answers %s", op, failure).isEqualTo(overHttp);
    }

    @Test
    void theMatrixCoversEveryAdapterOfTheCheckoutPathAndEveryFailureClass() {
        assertThat(operations().map(Op::adapter).distinct()).containsExactlyInAnyOrder("inventory", "payments", "coupons",
                "tax", "offerInventory", "productFacts", "markets", "memberBenefits", "pickupLocations",
                "shipFromAddresses", "addressBook", "staffDirectory", "runSlots", "preorders", "deliveryFeeRules");
        assertThat(failures().count()).isEqualTo(16);
    }

    /** A failure as the saga sees it, or the (equal-comparable) result. */
    private static Object outcome(Supplier<Object> call) {
        try {
            return "RESULT " + call.get();
        } catch (UpstreamFailure f) {
            return "FAILURE service=" + f.service() + " status=" + f.status() + " code=" + f.code() + " retryable="
                    + f.retryable() + " details=" + f.details() + " message=" + normalised(f);
        }
    }

    /** The message of a callee that never answered differs in its tail (the I/O exception); the rest must match. */
    private static String normalised(UpstreamFailure f) {
        String m = f.getMessage();
        return f.status() == 0 && m != null && m.contains(" did not answer") ? f.service() + " did not answer" : m;
    }

    private static Object run(Runnable r) {
        r.run();
        return "void";
    }

    /** Runs {@code call} with a signed-in customer in the security context (what the request thread has at checkout). */
    private static Object signedIn(Supplier<Object> call) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", "customer-1", "pt", "CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static DeliveryRunSlots runSlots(Wiring w) {
        return w.http() ? HttpWiring.runSlots(w.url(), TIMEOUT) : InProcessWiring.runSlots(w.api(DeliveryRunsApi.class));
    }

    private static PreorderEligibility preorders(Wiring w) {
        return w.http() ? HttpWiring.preorders(w.url(), TIMEOUT) : InProcessWiring.preorders(w.api(DeliveryRunsApi.class));
    }

    private static DeliveryFeeRules feeRules(Wiring w) {
        return w.http() ? HttpWiring.feeRules(w.url(), TIMEOUT) : InProcessWiring.feeRules(w.api(DeliveryRunsApi.class));
    }

    private static AddressBook addressBook(Wiring w) {
        return w.http() ? HttpWiring.addressBook(w.url(), TIMEOUT)
                : InProcessWiring.addressBook(w.api(AddressBookApi.class), new EndUsers(true));
    }

    private static StaffDirectory staff(Wiring w) {
        return w.http() ? HttpWiring.staff(w.url(), TIMEOUT) : InProcessWiring.staff(w.api(StaffDirectoryApi.class));
    }

    private static InventoryClient inventory(Wiring w) {
        return w.http() ? HttpWiring.inventory(w.url(), TIMEOUT) : InProcessWiring.inventory(w.api(ReservationApi.class));
    }

    private static PaymentsClient payments(Wiring w) {
        return w.http() ? HttpWiring.payments(w.url(), TIMEOUT) : InProcessWiring.payments(w.api(OrderPaymentsApi.class));
    }

    private static CouponsClient coupons(Wiring w) {
        return w.http() ? HttpWiring.coupons(w.url(), TIMEOUT) : InProcessWiring.coupons(w.api(CheckoutOffersApi.class));
    }

    private static TaxQuoteClient tax(Wiring w) {
        return w.http() ? HttpWiring.tax(w.url(), TIMEOUT) : InProcessWiring.tax(w.api(TaxQuoteApi.class));
    }

    private static OfferInventory offerInventory(Wiring w) {
        return w.http() ? HttpWiring.offerInventory(w.url(), TIMEOUT)
                : InProcessWiring.offerInventory(w.api(CheckoutOffersApi.class));
    }

    private static ProductFactsClient facts(Wiring w) {
        return w.http() ? HttpWiring.productFacts(w.url(), TIMEOUT, SETTINGS)
                : InProcessWiring.productFacts(w.api(ProductListingApi.class), SETTINGS);
    }

    private static MarketEligibility markets(Wiring w) {
        return w.http() ? HttpWiring.markets(w.url(), TIMEOUT) : InProcessWiring.markets(w.api(MarketEligibilityApi.class));
    }

    private static MemberBenefits benefits(Wiring w) {
        return w.http() ? HttpWiring.benefits(w.url(), TIMEOUT) : InProcessWiring.benefits(w.api(MembershipBenefitsApi.class));
    }

    private static PickupLocations pickups(Wiring w) {
        if (w.http()) {
            return HttpWiring.pickupLocations(w.url(), TIMEOUT);
        }
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("offers", w.api(CheckoutOffersApi.class));
        beans.registerSingleton("sellers", w.api(ShipFromAddressApi.class));
        beans.registerSingleton("callers", (CallerPrincipals) module -> InProcessWiring.ORDERS);
        ModuleUpstream upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
        return new InProcessPickupLocations(beans.getBeanProvider(CheckoutOffersApi.class),
                beans.getBeanProvider(ShipFromAddressApi.class), upstream, beans);
    }

    private static ShipFromAddresses shipFrom(Wiring w) {
        if (w.http()) {
            return HttpWiring.shipFrom(w.url(), TIMEOUT);
        }
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("nodes", w.api(NodeAddressApi.class));
        beans.registerSingleton("sellers", w.api(ShipFromAddressApi.class));
        beans.registerSingleton("callers", (CallerPrincipals) module -> InProcessWiring.ORDERS);
        ModuleUpstream upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
        return new InProcessShipFromAddresses(beans.getBeanProvider(NodeAddressApi.class),
                beans.getBeanProvider(ShipFromAddressApi.class), upstream, beans);
    }
}
