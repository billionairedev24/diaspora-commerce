package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.afrotransact.ai.port.AccountReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * The shopper's own orders (orders {@code GET /v1/orders?customer=me}), cart (cart {@code GET /v1/cart}) and bookings
 * (bookings {@code GET /v1/bookings/mine}) under their token. Anything that fails is "not available".
 */
@Component
class HttpAccountReader implements AccountReader {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAccountReader.class);

    private final RestClient orders;
    private final RestClient cart;
    private final RestClient bookings;

    HttpAccountReader(RestClient.Builder builder, @Value("${at.ai.downstream.orders-url}") String ordersUrl,
                      @Value("${at.ai.downstream.cart-url:http://localhost:8203}") String cartUrl,
                      @Value("${at.ai.downstream.bookings-url}") String bookingsUrl,
                      @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.orders = Downstream.client(builder, ordersUrl, timeout);
        this.cart = Downstream.client(builder, cartUrl, timeout);
        this.bookings = Downstream.client(builder, bookingsUrl, timeout);
    }

    @Override
    public Result<List<OrderSummary>> recentOrders(String bearerToken, int limit) {
        return read("orders", () -> {
            JsonNode body = orders.get().uri(u -> u.path("/v1/orders").queryParam("customer", "me")
                    .queryParam("limit", limit).build()).headers(h -> Downstream.relay(h, bearerToken)).retrieve()
                    .body(JsonNode.class);
            List<OrderSummary> list = new ArrayList<>();
            for (JsonNode o : items(body)) {
                String placed = Downstream.text(o, "placedAt");
                list.add(new OrderSummary(Downstream.text(o, "orderNumber"), Downstream.text(o, "status"),
                        placed.isBlank() ? Downstream.text(o, "createdAt") : placed, Downstream.number(o, "totalMinor"),
                        Downstream.text(o, "currency"), o.path("shipments").size()));
            }
            return list;
        });
    }

    @Override
    public Result<CartSummary> cart(String bearerToken) {
        return read("cart", () -> {
            JsonNode body = cart.get().uri("/v1/cart").headers(h -> Downstream.relay(h, bearerToken)).retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                return null;
            }
            List<CartLine> lines = new ArrayList<>();
            for (JsonNode p : body.path("packages")) {
                for (JsonNode l : p.path("lines")) {
                    String atin = Downstream.text(l, "variantAtin");
                    lines.add(new CartLine(atin.isBlank() ? Downstream.text(l, "atin") : atin, Downstream.text(l, "title"),
                            l.path("quantity").asInt(0), Downstream.number(l, "priceMinor"),
                            l.path("unavailable").asBoolean(false), l.path("priceChanged").asBoolean(false)));
                }
            }
            return new CartSummary(body.path("itemCount").asInt(lines.size()), Downstream.number(body, "subtotalMinor"),
                    Downstream.text(body, "currency"), body.path("checkoutable").asBoolean(false), lines);
        });
    }

    @Override
    public Result<List<BookingSummary>> bookings(String bearerToken) {
        return read("bookings", () -> {
            JsonNode body = bookings.get().uri("/v1/bookings/mine").headers(h -> Downstream.relay(h, bearerToken))
                    .retrieve().body(JsonNode.class);
            List<BookingSummary> list = new ArrayList<>();
            for (JsonNode b : items(body)) {
                JsonNode s = b.path("service");
                String status = Downstream.text(b, "statusLabel");
                list.add(new BookingSummary(Downstream.text(b, "reference"), Downstream.text(s, "name"),
                        status.isBlank() ? Downstream.text(b, "status") : status, Downstream.text(b, "startAt"),
                        Downstream.number(s, "priceMinor"), Downstream.number(s, "depositMinor")));
            }
            return list;
        });
    }

    private static Iterable<JsonNode> items(JsonNode body) {
        if (body == null) {
            return List.of();
        }
        return body.isArray() ? body : body.path("items");
    }

    private <T> Result<T> read(String what, Supplier<T> call) {
        try {
            T value = call.get();
            return value == null ? Result.unavailable() : Result.of(value);
        } catch (RestClientException e) {
            LOG.debug("{} service unavailable: {}", what, e.getClass().getSimpleName());
            return Result.unavailable();
        }
    }
}
