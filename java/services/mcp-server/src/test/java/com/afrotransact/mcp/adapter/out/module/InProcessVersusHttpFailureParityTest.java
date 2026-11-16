package com.afrotransact.mcp.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.afrotransact.bookings.api.UserServiceSearchApi;
import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.inventory.api.InventoryUserApi;
import com.afrotransact.mcp.adapter.out.http.HttpWiring;
import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.BookingsApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.SellerApi;
import com.afrotransact.mcp.port.TaxApi;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleCallTimeoutException;
import com.afrotransact.orders.api.OrdersUserApi;
import com.afrotransact.payments.api.PayoutReadApi;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.seller.api.SellerUserApi;
import com.afrotransact.taxreturns.api.TaxReviewReadApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Release 1 turned the HTTP adapters of mcp-server's tools into in-process ones behind the same ports; a tool must not notice. For
 * every adapter and every operation, this runs the SAME callee failure through both: the HTTP adapter against a real local HTTP server
 * that answers the status and RFC 9457 problem a callee advice would, and the in-process adapter over a callee API that throws the
 * {@link ModuleApiException} the callee's failure mapping gives for that status. The outcomes must be equal: the same {@link Downstream}
 * (OK with the same body, NOT_FOUND, FORBIDDEN, REJECTED or UNAVAILABLE), which is everything the tools look at.
 *
 * <p>Callee failures covered: 400, 401, 403, 404, 408, 409, 422, 429, 500, 502, 503, the callee crashing or timing out (HTTP: read
 * timeout; in-process: an unexpected exception), the bounded wait of the in-process gate and a full bulkhead (503 at once). The success
 * path is covered with the same body.
 */
class InProcessVersusHttpFailureParityTest {

    private static final Duration TIMEOUT = Duration.ofMillis(400);
    private static final Map<String, Object> BODY = Map.of("a", 1, "b", List.of("x"));

    /** What the scripted callee answers now. */
    private static volatile Callee callee;
    private static HttpServer server;
    private static String url;

    /** One callee outcome: the HTTP answer and the exception the called module's failure mapping throws for it. */
    record Callee(String name, int status, String code, boolean crash) {

        @Override
        public String toString() {
            return name;
        }

        RuntimeException thrown() {
            if (name.startsWith("bounded wait")) {
                return new ModuleCallTimeoutException("mcp-server", "callee", TIMEOUT);
            }
            return crash ? new IllegalStateException("could not execute statement; password=hunter2")
                    : new ModuleApiException(status, code, "detail", List.of(), null);
        }
    }

    /** One operation of one adapter, run through a port built by the supplied wiring. */
    record Op(String adapter, String name, Function<Wiring, Downstream> call) {

        @Override
        public String toString() {
            return adapter + "." + name;
        }
    }

    /** Both ways of reaching the same callee outcome. */
    record Wiring(boolean http, String url, Supplier<RuntimeException> fault) {

        <A> A api(Class<A> type) {
            InvocationHandler handler = (proxy, method, args) -> {
                RuntimeException e = fault.get();
                if (e == null) {
                    return BODY;
                }
                throw e;
            };
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }

    private static final CallerCredentials CREDENTIALS = new CallerCredentials("tok",
            () -> InProcessKit.user("u-1", "pt", "STAFF", "perms", List.of("x")));

    @BeforeAll
    static void startCallee() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Callee c = callee;
            try {
                exchange.getRequestBody().readAllBytes();
                if (c != null && c.crash()) {
                    Thread.sleep(TIMEOUT.toMillis() * 4);
                }
                boolean ok = c == null;
                String json = ok ? "{\"a\":1,\"b\":[\"x\"]}" : "{\"type\":\"about:blank\",\"status\":" + c.status() + ",\"code\":\""
                        + c.code() + "\",\"detail\":\"detail\"}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", ok ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(ok ? 200 : c.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException | InterruptedException e) {
                // the client gave up (the timeout case)
            } finally {
                exchange.close();
            }
        });
        // a callee that never answers must not hold up the next request: one thread per request
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopCallee() {
        server.stop(0);
    }

    static Stream<Callee> failures() {
        return Stream.of(
                new Callee("400 validation", 400, "x.invalid-input", false),
                new Callee("401 unauthenticated", 401, "auth.unauthenticated", false),
                new Callee("403 forbidden", 403, "auth.missing-permission", false),
                new Callee("403 staff only", 403, "auth.staff-only", false),
                new Callee("404 not found", 404, "x.not-found", false),
                new Callee("408 request timeout", 408, "x.timeout", false),
                new Callee("409 conflict", 409, "x.invalid-state", false),
                new Callee("422 rule violation", 422, "x.rule", false),
                new Callee("429 rate limited", 429, "x.rate-limited", false),
                new Callee("500 server error", 500, "x.boom", false),
                new Callee("502 upstream unavailable", 502, "x.upstream-unavailable", false),
                new Callee("503 unavailable", 503, "x.unavailable", false),
                // the callee module's bulkhead is full: the gate answers 503 <callee>.unavailable at once, as a 503 over HTTP
                new Callee("bulkhead full", 503, "callee.unavailable", false),
                new Callee("crash or timeout", 500, "x.crash", true),
                // the bounded wait of the in-process gate fires: over HTTP that is the read timeout (the server never answers)
                new Callee("bounded wait timeout", 500, "x.timeout", true));
    }

    static Stream<Op> operations() {
        return Stream.of(
                new Op("catalog", "publicProduct", w -> catalog(w).publicProduct("AT1", CREDENTIALS)),
                new Op("catalog", "publicVariant", w -> catalog(w).publicVariant("AT1", CREDENTIALS)),
                new Op("catalog", "products", w -> catalog(w).products("org", "IN_REVIEW", "q", 0, 25, CREDENTIALS)),
                new Op("catalog", "product by id", w -> catalog(w).product("11111111-1111-1111-1111-111111111111", CREDENTIALS)),
                new Op("catalog", "product by atin", w -> catalog(w).product("AT1AAAAAAA", CREDENTIALS)),
                new Op("catalog", "requestCategory", w -> catalog(w).requestCategory(Map.of("name", "Suya"), CREDENTIALS)),
                new Op("catalog", "categoryRequests", w -> catalog(w).categoryRequests("PENDING", CREDENTIALS)),
                new Op("inventory", "availability", w -> inventory(w).availability(List.of("S1", "S2"), CREDENTIALS)),
                new Op("inventory", "positions", w -> inventory(w).positions("org", "S1", true, CREDENTIALS)),
                new Op("orders", "order", w -> orders(w).order("AT-1", CREDENTIALS)),
                new Op("orders", "myOrders", w -> orders(w).myOrders("c", 20, CREDENTIALS)),
                new Op("orders", "sellerShipments", w -> orders(w).sellerShipments("packed", "c", 25, CREDENTIALS)),
                new Op("orders", "transitionShipment",
                        w -> orders(w).transitionShipment("s1", Map.of("to", "packed"), CREDENTIALS)),
                new Op("orders", "attachTracking", w -> orders(w).attachTracking("s1", "usps", "940", CREDENTIALS)),
                new Op("orders", "paymentSplit", w -> orders(w).paymentSplit("AT-1", CREDENTIALS)),
                new Op("payments", "payoutBalance", w -> payments(w).payoutBalance("org", CREDENTIALS)),
                new Op("payments", "payoutRequests", w -> payments(w).payoutRequests("org", 20, CREDENTIALS)),
                new Op("seller", "accountHealth", w -> seller(w).accountHealth("org", CREDENTIALS)),
                new Op("applications", "queue", w -> applications(w).queue("IN_REVIEW", 0, 25, CREDENTIALS)),
                new Op("applications", "application", w -> applications(w).application("a1", CREDENTIALS)),
                new Op("bookings", "findServices", w -> bookings(w).findServices("braids", "77002", CREDENTIALS)),
                new Op("tax", "reviewQueue", w -> tax(w).reviewQueue("PENDING_REVIEW", "q", 0, 25, CREDENTIALS)),
                new Op("tax", "shadowReport", w -> tax(w).shadowReport("2026-01-01", "2026-01-31", "AVALARA", 25, CREDENTIALS)));
    }

    static Stream<Arguments> matrix() {
        return failures().flatMap(f -> operations().map(o -> Arguments.of(f, o)));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    void everyFailureOfTheCalleeIsTheSameOutcomeInProcessAsOverHttp(Callee failure, Op op) {
        callee = failure;
        Downstream overHttp = op.call().apply(new Wiring(true, url, failure::thrown));
        Downstream inProcess = op.call().apply(new Wiring(false, url, failure::thrown));
        assertThat(inProcess).as("%s in-process vs HTTP", op).isEqualTo(overHttp);
        assertThat(inProcess.ok()).isFalse();
        callee = null;
    }

    @Test
    void aSuccessIsTheSameBodyInProcessAsOverHttp() {
        callee = null;
        List<Op> ops = operations().toList();
        assertThat(ops).hasSize(23);
        for (Op op : ops) {
            Downstream overHttp = op.call().apply(new Wiring(true, url, () -> null));
            Downstream inProcess = op.call().apply(new Wiring(false, url, () -> null));
            assertThat(overHttp.ok()).as("%s over HTTP", op).isTrue();
            assertThat(inProcess).as("%s in-process vs HTTP", op).isEqualTo(overHttp);
        }
    }

    private static CatalogApi catalog(Wiring w) {
        return w.http() ? HttpWiring.catalog(w.url(), TIMEOUT) : InProcessWiring.catalog(w.api(CatalogUserApi.class));
    }

    private static InventoryApi inventory(Wiring w) {
        return w.http() ? HttpWiring.inventory(w.url(), TIMEOUT) : InProcessWiring.inventory(w.api(InventoryUserApi.class));
    }

    private static OrdersApi orders(Wiring w) {
        return w.http() ? HttpWiring.orders(w.url(), TIMEOUT) : InProcessWiring.orders(w.api(OrdersUserApi.class));
    }

    private static PaymentsApi payments(Wiring w) {
        return w.http() ? HttpWiring.payments(w.url(), TIMEOUT) : InProcessWiring.payments(w.api(PayoutReadApi.class));
    }

    private static SellerApi seller(Wiring w) {
        return w.http() ? HttpWiring.seller(w.url(), TIMEOUT) : InProcessWiring.seller(w.api(SellerUserApi.class));
    }

    private static ApplicationsApi applications(Wiring w) {
        return w.http() ? HttpWiring.applications(w.url(), TIMEOUT) : InProcessWiring.applications(w.api(SellerUserApi.class));
    }

    private static BookingsApi bookings(Wiring w) {
        return w.http() ? HttpWiring.bookings(w.url(), TIMEOUT) : InProcessWiring.bookings(w.api(UserServiceSearchApi.class));
    }

    private static TaxApi tax(Wiring w) {
        return w.http() ? HttpWiring.tax(w.url(), TIMEOUT) : InProcessWiring.tax(w.api(TaxReviewReadApi.class));
    }
}
