package com.afrotransact.ai.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.BookingHistory;
import com.afrotransact.ai.port.CatalogReader;
import com.afrotransact.ai.port.OfferReader;
import com.afrotransact.ai.port.OrderHistory;
import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.ReturnHistory;
import com.afrotransact.ai.port.ServiceDirectory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.bookings.api.ShopperBookingsApi;
import com.afrotransact.catalog.api.PublicProductApi;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.api.PublicOffersApi;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.taxreturns.api.ShopperReturnsApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Lola's readers in-process: they act for the person captured at request entry (the worker thread has no security context), as
 * ai-gateway's caller principal and nothing else, and fail like the HTTP readers (never throwing: "not available", "not found" or empty).
 */
class InProcessReadersTest {

    private final EndUserActor person = InProcessKit.user("customer-1", "pt", "CUSTOMER");
    private final Shopper shopper = new Shopper("a-bearer-string-nobody-reads", person);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<EndUserActor> actors = new CopyOnWriteArrayList<>();
    private final List<String> callers = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void note(String what, EndUserActor user) {
        calls.add(what);
        actors.add(user);
        callers.add(InProcessCalls.require().clientId());
        if (failure != null) {
            throw failure;
        }
    }

    private final ShopperOrdersApi ordersApi = new ShopperOrdersApi() {
        @Override
        public List<OrderSummary> mine(EndUserActor user, int limit) {
            note("orders.mine(" + limit + ")", user);
            return List.of(new OrderSummary("AT-1", "SHIPPED", Instant.parse("2026-10-12T10:00:00Z"), 4599, "USD", 2));
        }

        @Override
        public TrackedOrder track(EndUserActor user, String numberOrId) {
            note("orders.track(" + numberOrId + ")", user);
            return new TrackedOrder("AT-1", "SHIPPED", List.of(new TrackedShipment("shp-1", "IN_TRANSIT", "UPS", "1Z9",
                    LocalDate.parse("2026-10-20"))));
        }
    };

    @Test
    void ordersAreReadAsThePersonCapturedAtRequestEntryOnAThreadWithoutASecurityContext() throws Exception {
        OrderTracker tracker = InProcessWiring.tracker(ordersApi);
        OrderHistory history = InProcessWiring.orders(ordersApi);
        ExecutorService lolaWorker = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // the request thread's security context is NOT visible on the worker; only the captured person is
            OrderTracker.Lookup lookup = lolaWorker.submit(() -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                return tracker.track("AT-1", shopper);
            }).get();
            assertThat(lookup).isEqualTo(new OrderTracker.Lookup(OrderTracker.Availability.FOUND, "AT-1", "SHIPPED", List.of(
                    new OrderTracker.Shipment("shp-1", "IN_TRANSIT", "UPS", "1Z9", "2026-10-20"))));
            AccountReader.Result<List<AccountReader.OrderSummary>> orders = lolaWorker.submit(() -> history.recent(shopper, 5)).get();
            assertThat(orders.available()).isTrue();
            assertThat(orders.value()).containsExactly(new AccountReader.OrderSummary("AT-1", "SHIPPED", "2026-10-12T10:00:00Z",
                    4599, "USD", 2));
        } finally {
            lolaWorker.shutdown();
        }
        assertThat(calls).containsExactly("orders.track(AT-1)", "orders.mine(5)");
        assertThat(actors).containsExactly(person, person);
        assertThat(callers).containsOnly("ai-gateway-service");
    }

    @Test
    void noPersonMeansNothingIsCalledAndTheReadIsNotAvailableAsTheHttp401IsNotAvailable() {
        Shopper nobody = Shopper.token("a-bearer-string-is-not-an-identity");
        assertThat(InProcessWiring.tracker(ordersApi).track("AT-1", nobody).availability()).isEqualTo(OrderTracker.Availability.UNAVAILABLE);
        assertThat(InProcessWiring.orders(ordersApi).recent(nobody, 5).available()).isFalse();
        assertThat(InProcessWiring.bookings(user -> {
            throw new AssertionError("must not be called");
        }).mine(nobody).available()).isFalse();
        assertThat(InProcessWiring.returns((user, limit) -> {
            throw new AssertionError("must not be called");
        }).mine(nobody, "").available()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void theSecurityContextOfTheCallingThreadIsNotWhatDecidesWhoIsRead() {
        // a different person signed in on this thread must not replace the captured one
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject("customer-2")
                .claim("pt", "CUSTOMER").build()));
        SecurityContextHolder.setContext(context);
        InProcessWiring.orders(ordersApi).recent(shopper, 3);
        assertThat(actors).singleElement().extracting(EndUserActor::userId).isEqualTo("customer-1");
    }

    @Test
    void trackingMapsTheRefusalsLikeTheHttpAdapter() {
        OrderTracker tracker = InProcessWiring.tracker(ordersApi);
        for (int status : new int[] {404, 403}) {
            failure = new ModuleApiException(status, "orders.x", "no");
            assertThat(tracker.track("AT-9", shopper).availability()).as("status " + status).isEqualTo(OrderTracker.Availability.NOT_FOUND);
        }
        for (int status : new int[] {400, 401, 409, 422, 429, 500, 503}) {
            failure = new ModuleApiException(status, "orders.x", "no");
            assertThat(tracker.track("AT-9", shopper).availability()).as("status " + status).isEqualTo(OrderTracker.Availability.UNAVAILABLE);
        }
        failure = new IllegalStateException("could not execute statement; password=hunter2");
        OrderTracker.Lookup crashed = tracker.track("AT-9", shopper);
        assertThat(crashed.availability()).isEqualTo(OrderTracker.Availability.UNAVAILABLE);
        assertThat(crashed.toString()).doesNotContain("hunter2");
    }

    @Test
    void bookingsAndReturnsAreTheShoppersOwnAndAFailureIsNotAvailable() {
        ShopperBookingsApi bookingsApi = user -> {
            note("bookings.mine", user);
            return List.of(new ShopperBookingsApi.BookingSummary("BK-77", "Knotless braids", "Confirmed",
                    Instant.parse("2026-10-25T14:00:00Z"), 15000, 3000));
        };
        ShopperReturnsApi returnsApi = (user, limit) -> {
            note("returns.mine(" + limit + ")", user);
            return List.of(
                    new ShopperReturnsApi.ReturnSummary("RMA-1", "AT-2026-000123", "REQUESTED", "DROP_OFF", "NONE", 2599, "USD", null,
                            Instant.parse("2026-10-20T10:00:00Z"), List.of(new ShopperReturnsApi.ReturnLine("Garri 2kg", 2, "DAMAGED", null))),
                    new ShopperReturnsApi.ReturnSummary("RMA-2", "AT-2026-000124", "REFUNDED", "DROP_OFF", "ISSUED", 1200, "USD",
                            Instant.parse("2026-10-30T00:00:00Z"), null, List.of()));
        };
        BookingHistory bookings = InProcessWiring.bookings(bookingsApi);
        ReturnHistory returns = InProcessWiring.returns(returnsApi);
        assertThat(bookings.mine(shopper).value()).containsExactly(new AccountReader.BookingSummary("BK-77", "Knotless braids",
                "Confirmed", "2026-10-25T14:00:00Z", 15000, 3000));
        assertThat(returns.mine(shopper, "").value()).extracting(AccountReader.ReturnSummary::number).containsExactly("RMA-1", "RMA-2");
        assertThat(returns.mine(shopper, "at-2026-000124").value()).extracting(AccountReader.ReturnSummary::number).containsExactly("RMA-2");
        assertThat(returns.mine(shopper, "").value().get(0)).isEqualTo(new AccountReader.ReturnSummary("RMA-1", "AT-2026-000123",
                "REQUESTED", "DROP_OFF", "NONE", 2599, "USD", "", "2026-10-20T10:00:00Z",
                List.of(new AccountReader.ReturnLine("Garri 2kg", 2, "DAMAGED", ""))));
        assertThat(actors).containsOnly(person);
        for (RuntimeException f : List.<RuntimeException>of(new ModuleApiException(403, "auth.missing-permission", "no"),
                new ModuleApiException(503, "tax-returns.unavailable", "full"), new IllegalStateException("boom"))) {
            failure = f;
            assertThat(bookings.mine(shopper).available()).isFalse();
            assertThat(returns.mine(shopper, "").available()).isFalse();
        }
    }

    @Test
    void publicReadsRunAsAiGatewayWithNoPersonAndAnyFailureIsEmpty() {
        PublicProductApi catalogApi = atin -> {
            note("catalog.find(" + atin + ")", null);
            return Optional.of(new PublicProductApi.PublicItem(atin, "ATP", "Garri", Map.of("size", "2kg"), 899, "USD", "org_malo"));
        };
        PublicOffersApi offersApi = (atin, currency) -> {
            note("offers.offersForProduct(" + atin + "," + currency + ")", null);
            return List.of(new PublicOffersApi.OfferRow("o1", "Malo", "SELLER", "SELLER", 899, "USD", true, 3, "NEW"));
        };
        ServiceSearchApi servicesApi = (query, zip, size) -> {
            note("services.search(" + query + "," + zip + "," + size + ")", null);
            return List.of(new ServiceSearchApi.ServiceListing("s1", "Knotless braids", "Ada", null, 15000, null, 3000, 4.5, null, "Houston"));
        };
        CatalogReader catalog = InProcessWiring.catalog(catalogApi);
        OfferReader offers = InProcessWiring.offers(offersApi);
        ServiceDirectory services = InProcessWiring.services(servicesApi);
        assertThat(catalog.find("ATV", shopper)).contains(new CatalogReader.Item("ATV", "ATP", "Garri", Map.of("size", "2kg"), 899, "USD",
                "org_malo"));
        assertThat(offers.offers("ATP", shopper)).containsExactly(new OfferReader.Offer("o1", "Malo", "SELLER", "SELLER", 899, "USD", true, 3,
                "NEW"));
        assertThat(services.find("braids", "77002", shopper)).containsExactly(new ServiceDirectory.Listing("s1", "Knotless braids", "Ada", "",
                15000, "USD", 3000, 4.5, "", "Houston"));
        assertThat(calls).containsExactly("catalog.find(ATV)", "offers.offersForProduct(ATP,USD)", "services.search(braids,77002,6)");
        assertThat(actors).containsOnlyNulls();
        assertThat(callers).containsOnly("ai-gateway-service");
        for (RuntimeException f : List.<RuntimeException>of(new ModuleApiException(404, "x", "no"), new ModuleApiException(422, "bookings.invalid-zip", "no"),
                new ModuleApiException(503, "x.unavailable", "bulkhead full"), new IllegalStateException("boom"))) {
            failure = f;
            assertThat(catalog.find("ATV", shopper)).isEmpty();
            assertThat(offers.offers("ATP", shopper)).isEmpty();
            assertThat(services.find("braids", null, shopper)).isEmpty();
        }
    }

    @Test
    void startingInProcessWithoutTheCalleeFailsNamingBothModules() {
        var beans = new DefaultListableBeanFactory();
        assertThatThrownBy(() -> new InProcessOrderTracker(beans.getBeanProvider(ShopperOrdersApi.class), null, beans))
                .hasMessageContaining("ai-gateway").hasMessageContaining("orders").hasMessageContaining("at.aigateway.orders.mode");
    }
}
