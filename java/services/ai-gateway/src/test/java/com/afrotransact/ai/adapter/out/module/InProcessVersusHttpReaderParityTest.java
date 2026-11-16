package com.afrotransact.ai.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Stream;

import com.afrotransact.ai.adapter.out.http.HttpWiring;
import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.bookings.api.ShopperBookingsApi;
import com.afrotransact.catalog.api.PublicProductApi;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleCallTimeoutException;
import com.afrotransact.offers.api.PublicOffersApi;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.taxreturns.api.ShopperReturnsApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Release 1 turned Lola's HTTP readers into in-process ones behind the same ports; the tools must not notice. For each of the seven
 * readers this runs the SAME callee behaviour through both: the HTTP reader against a real local HTTP server that answers what the callee's
 * endpoint answers (its JSON, or the status of its failure), and the in-process reader over a callee API that returns the equivalent data or
 * throws the {@link ModuleApiException} the callee's failure mapping gives for that status. The answers must be equal: the same data, the same
 * "not found", the same "not available" or the same empty list.
 *
 * <p>Behaviours: success, 400, 403, 404, 408, 409, 422, 429, 500, 502, 503, the callee crashing or hanging (HTTP: a read timeout; in-process: an
 * unexpected exception), the bounded wait running out and the bulkhead rejecting (in-process only: the HTTP equivalents are the timeout and 503).
 */
class InProcessVersusHttpReaderParityTest {

    private static final Duration TIMEOUT = Duration.ofMillis(400);
    private static final Shopper SHOPPER = new Shopper("tok", InProcessKit.user("customer-1", "pt", "CUSTOMER"));

    private static volatile Behaviour behaviour;
    private static HttpServer server;
    private static String url;

    /** One scripted callee behaviour: what the HTTP endpoint answers and what the callee API throws; null status = success. */
    record Behaviour(String name, int status, boolean crash, boolean boundedWait) {

        @Override
        public String toString() {
            return name;
        }

        RuntimeException thrown() {
            if (boundedWait) {
                return new ModuleCallTimeoutException("ai-gateway", "callee", TIMEOUT);
            }
            return crash ? new IllegalStateException("could not execute statement; password=hunter2")
                    : new ModuleApiException(status, "callee.failure", "callee said no");
        }

        boolean ok() {
            return status == 0 && !crash && !boundedWait;
        }
    }

    /** One reader: how to run it over HTTP, how to run it in-process (the API faults with the supplied behaviour). */
    record Reader(String name, Function<String, Object> http, Function<Behaviour, Object> inProcess) {

        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeAll
    static void startCallee() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Behaviour b = behaviour;
            try {
                exchange.getRequestBody().readAllBytes();
                if (b.crash()) {
                    Thread.sleep(TIMEOUT.toMillis() * 4);
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                int status = b.ok() ? okStatus(path) : b.status();
                String body = status == 200 ? okBody(path) : "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":" + status
                        + ",\"code\":\"callee.failure\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        // a hanging callee must not block the next request: one thread per request
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopCallee() {
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();
    }

    /** The catalog's product endpoint is only asked after the variant endpoint missed: a success answers the variant one. */
    private static int okStatus(String path) {
        return path.startsWith("/v1/public/products/") && !path.endsWith("/buy-boxes") ? 404 : 200;
    }

    private static String okBody(String path) {
        if (path.startsWith("/v1/public/variants/")) {
            return "{\"product\":{\"atin\":\"ATP\",\"title\":\"Garri 2kg\",\"attributes\":{\"brand\":\"Malo\"},\"sellerOrgId\":\"org_malo\"},"
                    + "\"variant\":{\"options\":{\"size\":\"2kg\"},\"priceMinor\":899,\"currency\":\"USD\"}}";
        }
        if (path.endsWith("/buy-boxes")) {
            return "{\"items\":[{\"atin\":\"ATV\",\"buyBox\":{\"offerId\":\"o1\",\"seller\":{\"name\":\"Malo\",\"kind\":\"SELLER\"},"
                    + "\"fulfilment\":\"SELLER\",\"priceMinor\":899,\"currency\":\"USD\",\"stockStatus\":\"LOW_STOCK\",\"deliveryDays\":3,"
                    + "\"condition\":\"NEW\"},\"otherOffers\":[{\"offerId\":\"o2\",\"seller\":{\"name\":\"Ada\",\"kind\":\"SELLER\"},"
                    + "\"fulfilment\":\"AFROTRANSACT\",\"priceMinor\":949,\"currency\":\"USD\",\"stockStatus\":\"OUT_OF_STOCK\","
                    + "\"deliveryDays\":5,\"condition\":\"NEW\"}]}]}";
        }
        if (path.equals("/v1/orders")) {
            return "{\"items\":[{\"orderNumber\":\"AT-1\",\"status\":\"SHIPPED\",\"placedAt\":\"2026-10-12T10:00:00Z\",\"totalMinor\":4599,"
                    + "\"currency\":\"USD\",\"shipments\":[{},{}]}],\"nextCursor\":null,\"total\":1}";
        }
        if (path.startsWith("/v1/orders/")) {
            return "{\"orderNumber\":\"AT-1\",\"status\":\"SHIPPED\",\"shipments\":[{\"id\":\"shp-1\",\"status\":\"IN_TRANSIT\","
                    + "\"carrier\":\"UPS\",\"trackingNumber\":\"1Z9\",\"promisedBy\":\"2026-10-20\"}]}";
        }
        if (path.equals("/v1/bookings/mine")) {
            return "[{\"reference\":\"BK-77\",\"status\":\"CONFIRMED\",\"statusLabel\":\"Confirmed\",\"startAt\":\"2026-10-25T14:00:00Z\","
                    + "\"service\":{\"name\":\"Knotless braids\",\"priceMinor\":15000,\"depositMinor\":3000}}]";
        }
        if (path.equals("/v1/services/search")) {
            return "{\"services\":[{\"serviceId\":\"s1\",\"name\":\"Knotless braids\",\"providerName\":\"Ada\",\"description\":\"Waist\","
                    + "\"priceFromMinor\":15000,\"currency\":\"USD\",\"depositMinor\":3000,\"rating\":4.5,"
                    + "\"nextAvailable\":\"2026-10-25T14:00:00Z\",\"location\":\"Houston\"}]}";
        }
        if (path.equals("/v1/returns")) {
            return "[{\"number\":\"RMA-1\",\"orderNumber\":\"AT-1\",\"status\":\"REQUESTED\",\"method\":\"DROP_OFF\",\"refundState\":\"NONE\","
                    + "\"estimateMinor\":2599,\"currency\":\"USD\",\"dropOffBy\":null,\"createdAt\":\"2026-10-20T10:00:00Z\","
                    + "\"lines\":[{\"title\":\"Garri 2kg\",\"quantity\":2,\"reason\":\"DAMAGED\",\"outcome\":null}]}]";
        }
        throw new IllegalStateException(path);
    }

    /** The called modules' APIs, answering the data of {@link #okBody} or failing as scripted. */
    private static <T> T answer(Behaviour b, T ok) {
        if (!b.ok()) {
            throw b.thrown();
        }
        return ok;
    }

    private static Stream<Arguments> readersAndBehaviours() {
        List<Reader> readers = List.of(
                new Reader("catalog.find", u -> HttpWiring.catalog(u, TIMEOUT).find("ATV", SHOPPER),
                        b -> InProcessWiring.catalog(atin -> answer(b, Optional.of(
                                new PublicProductApi.PublicItem(atin, "ATP", "Garri 2kg", Map.of("brand", "Malo", "size", "2kg"), 899,
                                        "USD", "org_malo")))).find("ATV", SHOPPER)),
                new Reader("offers.offers", u -> HttpWiring.offers(u, TIMEOUT).offers("ATP", SHOPPER),
                        b -> InProcessWiring.offers((atin, currency) -> {
                            var rows = answer(b, List.of(
                                    new PublicOffersApi.OfferRow("o1", "Malo", "SELLER", "SELLER", 899, "USD", true, 3, "NEW"),
                                    new PublicOffersApi.OfferRow("o2", "Ada", "SELLER", "AFROTRANSACT", 949, "USD", false, 5, "NEW")));
                            return rows;
                        }).offers("ATP", SHOPPER)),
                new Reader("tracker.track", u -> HttpWiring.tracker(u, TIMEOUT).track("AT-1", SHOPPER),
                        b -> InProcessWiring.tracker(orders(b)).track("AT-1", SHOPPER)),
                new Reader("services.find", u -> HttpWiring.services(u, TIMEOUT).find("braids", "77002", SHOPPER),
                        b -> InProcessWiring.services((ServiceSearchApi) (query, zip, size) -> {
                            var rows = answer(b, List.of(
                                    new ServiceSearchApi.ServiceListing("s1", "Knotless braids", "Ada", "Waist", 15000, "USD", 3000, 4.5,
                                            "2026-10-25T14:00:00Z", "Houston")));
                            return rows;
                        }).find("braids", "77002", SHOPPER)),
                new Reader("orders.recent", u -> HttpWiring.orders(u, TIMEOUT).recent(SHOPPER, 5),
                        b -> InProcessWiring.orders(orders(b)).recent(SHOPPER, 5)),
                new Reader("bookings.mine", u -> HttpWiring.bookings(u, TIMEOUT).mine(SHOPPER),
                        b -> InProcessWiring.bookings((ShopperBookingsApi) user -> {
                            var rows = answer(b, List.of(
                                    new ShopperBookingsApi.BookingSummary("BK-77", "Knotless braids", "Confirmed",
                                            Instant.parse("2026-10-25T14:00:00Z"), 15000, 3000)));
                            return rows;
                        }).mine(SHOPPER)),
                new Reader("returns.mine", u -> HttpWiring.returns(u, TIMEOUT).mine(SHOPPER, ""),
                        b -> InProcessWiring.returns((ShopperReturnsApi) (user, limit) -> {
                            var rows = answer(b, List.of(
                                    new ShopperReturnsApi.ReturnSummary("RMA-1", "AT-1", "REQUESTED", "DROP_OFF", "NONE", 2599, "USD", null,
                                            Instant.parse("2026-10-20T10:00:00Z"),
                                            List.of(new ShopperReturnsApi.ReturnLine("Garri 2kg", 2, "DAMAGED", null)))));
                            return rows;
                        }).mine(SHOPPER, "")));
        List<Behaviour> behaviours = List.of(new Behaviour("success", 0, false, false), new Behaviour("400", 400, false, false),
                new Behaviour("403", 403, false, false), new Behaviour("404", 404, false, false),
                new Behaviour("408", 408, false, false), new Behaviour("409", 409, false, false),
                new Behaviour("422", 422, false, false), new Behaviour("429", 429, false, false),
                new Behaviour("500", 500, false, false), new Behaviour("502", 502, false, false),
                new Behaviour("503", 503, false, false), new Behaviour("callee crashes or hangs", 0, true, false),
                new Behaviour("bounded wait timeout", 0, false, true));
        return readers.stream().flatMap(r -> behaviours.stream().map(b -> Arguments.of(r, b)));
    }

    private static ShopperOrdersApi orders(Behaviour b) {
        return new ShopperOrdersApi() {
            @Override
            public List<OrderSummary> mine(EndUserActor user, int limit) {
                return answer(b, List.of(new OrderSummary("AT-1", "SHIPPED", Instant.parse("2026-10-12T10:00:00Z"),
                        4599, "USD", 2)));
            }

            @Override
            public TrackedOrder track(EndUserActor user, String numberOrId) {
                return answer(b, new TrackedOrder("AT-1", "SHIPPED", List.of(new TrackedShipment("shp-1", "IN_TRANSIT",
                        "UPS", "1Z9", LocalDate.parse("2026-10-20")))));
            }
        };
    }

    @ParameterizedTest(name = "{0} when the callee: {1}")
    @MethodSource("readersAndBehaviours")
    void theInProcessReaderAnswersWhatTheHttpReaderAnswers(Reader reader, Behaviour b) {
        behaviour = b;
        Object overHttp = reader.http().apply(url);
        Object inProcess = reader.inProcess().apply(b);
        assertThat(inProcess).isEqualTo(overHttp);
        if (b.ok()) {
            assertThat(overHttp).isNotEqualTo(Optional.empty()).isNotEqualTo(List.of());
            if (overHttp instanceof OrderTracker.Lookup lookup) {
                assertThat(lookup.availability()).isEqualTo(OrderTracker.Availability.FOUND);
            }
            if (overHttp instanceof AccountReader.Result<?> result) {
                assertThat(result.available()).isTrue();
            }
        }
        assertThat(String.valueOf(inProcess)).doesNotContain("hunter2").doesNotContain("callee said no");
    }
}
