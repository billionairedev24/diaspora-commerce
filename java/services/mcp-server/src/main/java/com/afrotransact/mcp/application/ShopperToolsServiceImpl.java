package com.afrotransact.mcp.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.mcp.port.BookingsApi;
import com.afrotransact.mcp.port.CartApi;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.GeoApi;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PromiseApi;
import com.afrotransact.mcp.port.SearchApi;
import org.springframework.stereotype.Service;

@Service
class ShopperToolsServiceImpl implements ShopperToolsService {

    private static final Set<String> RETURN_REASONS = Set.of("DAMAGED", "WRONG_ITEM", "NOT_AS_DESCRIBED", "EXPIRED",
            "NO_LONGER_NEEDED", "OTHER");

    private final ToolGuardService guard;
    private final SearchApi search;
    private final CatalogApi catalog;
    private final CartApi cart;
    private final GeoApi geo;
    private final PromiseApi promise;
    private final OrdersApi orders;
    private final BookingsApi bookings;

    ShopperToolsServiceImpl(ToolGuardService guard, SearchApi search, CatalogApi catalog, CartApi cart, GeoApi geo,
                            PromiseApi promise, OrdersApi orders, BookingsApi bookings) {
        this.guard = guard;
        this.search = search;
        this.catalog = catalog;
        this.cart = cart;
        this.geo = geo;
        this.promise = promise;
        this.orders = orders;
        this.bookings = bookings;
    }

    @Override
    public ToolResult searchProducts(McpCaller caller, String query, Long maxPriceMinor, Boolean inStockOnly,
                                     String community, Integer size) {
        guard.require(caller.profile(), ToolCatalog.SEARCH_PRODUCTS);
        if (query == null || query.isBlank()) {
            return ToolResult.of("REJECTED", "query is required");
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("q", query.trim());
        q.put("size", size == null ? 10 : Math.max(1, Math.min(size, 24)));
        if (maxPriceMinor != null) {
            q.put("maxPrice", maxPriceMinor);
        }
        if (inStockOnly == null || inStockOnly) {
            q.put("inStock", true);
        }
        if (community != null && !community.isBlank()) {
            q.put("community", community.trim());
        }
        return Results.from(search.search(q, caller.bearerToken()), "search");
    }

    @Override
    public ToolResult getProduct(McpCaller caller, String atin) {
        guard.require(caller.profile(), ToolCatalog.GET_PRODUCT);
        return Results.from(catalog.publicProduct(normalise(atin), caller.bearerToken()), "live product");
    }

    @Override
    public ToolResult checkDelivery(McpCaller caller, String atin, String zip) {
        guard.require(caller.profile(), ToolCatalog.CHECK_DELIVERY);
        if (zip == null || !zip.trim().matches("\\d{5}")) {
            return ToolResult.of("REJECTED", "Ask the user for a 5-digit US ZIP code");
        }
        Downstream place = geo.zip(zip.trim(), caller.bearerToken());
        if (place.status() == Downstream.Status.NOT_FOUND) {
            return ToolResult.of("NOT_FOUND", "Unknown ZIP code " + zip);
        }
        Downstream p = promise.promise(normalise(atin), zip.trim(), caller.bearerToken());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("atin", normalise(atin));
        data.put("zip", zip.trim());
        if (place.ok()) {
            data.put("place", place.body());
        }
        if (!p.ok()) {
            data.put("promise", null);
            return new ToolResult("UNAVAILABLE", data, "Delivery promises are not available yet; do not guess a date");
        }
        data.put("promise", p.body());
        return ToolResult.ok(data);
    }

    @Override
    public ToolResult getCart(McpCaller caller) {
        guard.require(caller.profile(), ToolCatalog.GET_CART);
        return Results.from(cart.cart(caller.bearerToken()), "cart");
    }

    @Override
    public ToolResult proposeAddToCart(McpCaller caller, String atin, Integer quantity) {
        guard.require(caller.profile(), ToolCatalog.PROPOSE_ADD_TO_CART);
        String a = normalise(atin);
        Downstream variant = catalog.publicVariant(a, caller.bearerToken());
        if (!variant.ok() && !catalog.publicProduct(a, caller.bearerToken()).ok()) {
            return ToolResult.of("NOT_FOUND", "No live product with ATIN " + a + "; search first");
        }
        int qty = quantity == null ? 1 : Math.max(1, Math.min(quantity, 99));
        return Results.proposal("ADD_TO_CART", "Add " + qty + " × " + a + " to cart", "POST", "/v1/cart/items",
                Map.of("variantAtin", a, "quantity", qty));
    }

    @Override
    public ToolResult myOrderHistory(McpCaller caller, String cursor) {
        guard.require(caller.profile(), ToolCatalog.MY_ORDER_HISTORY);
        return Results.from(orders.myOrders(cursor, 20, caller.bearerToken()), "order history");
    }

    @Override
    public ToolResult trackOrder(McpCaller caller, String orderId) {
        guard.require(caller.profile(), ToolCatalog.TRACK_ORDER);
        if (orderId == null || orderId.isBlank()) {
            return ToolResult.of("REJECTED", "Ask the user for their order number");
        }
        return Results.from(orders.order(orderId.trim(), caller.bearerToken()), "order tracking");
    }

    @Override
    public ToolResult startReturnProposal(McpCaller caller, String orderId, String reason, List<String> items) {
        guard.require(caller.profile(), ToolCatalog.START_RETURN_PROPOSAL);
        String r = reason == null ? "OTHER" : reason.trim().toUpperCase(Locale.ROOT);
        if (!RETURN_REASONS.contains(r)) {
            return ToolResult.of("REJECTED", "reason must be one of " + RETURN_REASONS);
        }
        // A delivered order has no self-service return: the proposal opens the help form, where the customer describes
        // the problem and adds photos for staff to resolve.
        String order = orderId == null ? "" : orderId.trim();
        return Results.proposal("START_RETURN", "Get help with order " + order, "GET",
                "/account/help/new?order=" + URLEncoder.encode(order, StandardCharsets.UTF_8),
                Map.of("orderNumber", order, "reason", r, "items", items == null ? List.of() : items));
    }

    @Override
    public ToolResult findService(McpCaller caller, String query, String zip) {
        guard.require(caller.profile(), ToolCatalog.FIND_SERVICE);
        Downstream d = bookings.findServices(query == null ? "" : query.trim(), zip, caller.bearerToken());
        if (d.status() == Downstream.Status.UNAVAILABLE) {
            return new ToolResult("UNAVAILABLE", Map.of("services", List.of()),
                    "Service search is not available yet; do not invent providers");
        }
        return Results.from(d, "service");
    }

    private static String normalise(String atin) {
        return atin == null ? "" : atin.trim().toUpperCase(Locale.ROOT);
    }
}
