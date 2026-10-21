package com.afrotransact.ai.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.ai.domain.guardrail.InjectionFilter;
import com.afrotransact.ai.domain.lola.ActionProposal;
import com.afrotransact.ai.domain.lola.ProductCard;
import com.afrotransact.ai.domain.lola.ProposalType;
import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.CatalogReader;
import com.afrotransact.ai.port.DeliveryPromises;
import com.afrotransact.ai.port.OfferReader;
import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.ProductSearch;
import com.afrotransact.ai.port.ServiceDirectory;
import com.afrotransact.ai.port.ToolCallRequest;
import com.afrotransact.ai.port.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class LolaToolkitImpl implements LolaToolkit {

    private static final Logger LOG = LoggerFactory.getLogger(LolaToolkitImpl.class);
    private static final int MAX_SELLER_TEXT = 300;

    private final ProductSearch search;
    private final CatalogReader catalog;
    private final OfferReader offers;
    private final DeliveryPromises promises;
    private final OrderTracker orders;
    private final ServiceDirectory services;
    private final AccountReader account;
    private final InjectionFilter injection;
    private final JsonMapper json;
    private final AiSettings settings;
    private final AiMetrics metrics;

    LolaToolkitImpl(ProductSearch search, CatalogReader catalog, OfferReader offers, DeliveryPromises promises,
                    OrderTracker orders, ServiceDirectory services, AccountReader account, InjectionFilter injection,
                    JsonMapper json,
                    AiSettings settings, AiMetrics metrics) {
        this.search = search;
        this.catalog = catalog;
        this.offers = offers;
        this.promises = promises;
        this.orders = orders;
        this.services = services;
        this.account = account;
        this.injection = injection;
        this.json = json;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Override
    public List<ToolSpec> specs() {
        return LolaToolSpecs.ALL;
    }

    @Override
    public Invocation describe(ToolCallRequest call) {
        JsonNode a = args(call);
        String label = switch (call.name()) {
            case LolaToolSpecs.SEARCH_PRODUCTS -> "Searching for " + text(a, "query");
            case LolaToolSpecs.COMPARE_OFFERS -> "Comparing sellers for " + text(a, "atin");
            case LolaToolSpecs.CHECK_DELIVERY -> text(a, "zip").isEmpty() ? "Checking delivery"
                    : "Checking delivery to " + text(a, "zip");
            case LolaToolSpecs.ADD_TO_CART -> "Getting " + text(a, "atin") + " ready for your cart";
            case LolaToolSpecs.BUILD_CART_FROM_RECIPE -> "Building a cart for " + text(a, "dish")
                    + (a.path("servings").asInt(0) > 0 ? " for " + a.path("servings").asInt(0) : "");
            case LolaToolSpecs.TRACK_ORDER -> "Tracking order " + text(a, "orderId");
            case LolaToolSpecs.MY_ORDERS -> "Looking at your recent orders";
            case LolaToolSpecs.VIEW_CART -> "Looking at your cart";
            case LolaToolSpecs.MY_BOOKINGS -> "Looking at your bookings";
            case LolaToolSpecs.START_RETURN -> "Preparing a return for order " + text(a, "orderId");
            case LolaToolSpecs.FIND_SERVICE -> "Finding " + text(a, "query");
            case LolaToolSpecs.BOOK_SERVICE -> "Preparing your booking";
            case LolaToolSpecs.PROCEED_TO_CHECKOUT -> "Opening checkout";
            case LolaToolSpecs.HANDOFF_TO_SUPPORT -> "Connecting you with our support team";
            default -> "Working on it";
        };
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = a.isObject() ? json.convertValue(a, Map.class) : Map.of();
        return new Invocation(label, parsed);
    }

    @Override
    public ToolOutcome execute(ToolCallRequest call, ToolContext ctx) {
        JsonNode a = args(call);
        ToolOutcome outcome;
        try {
            outcome = switch (call.name()) {
                case LolaToolSpecs.SEARCH_PRODUCTS -> searchProducts(a, ctx);
                case LolaToolSpecs.COMPARE_OFFERS -> compareOffers(a, ctx);
                case LolaToolSpecs.CHECK_DELIVERY -> checkDelivery(a, ctx);
                case LolaToolSpecs.ADD_TO_CART -> addToCart(a, ctx);
                case LolaToolSpecs.BUILD_CART_FROM_RECIPE -> buildCart(a, ctx);
                case LolaToolSpecs.TRACK_ORDER -> trackOrder(a, ctx);
                case LolaToolSpecs.MY_ORDERS -> myOrders(a, ctx);
                case LolaToolSpecs.VIEW_CART -> viewCart(ctx);
                case LolaToolSpecs.MY_BOOKINGS -> myBookings(ctx);
                case LolaToolSpecs.START_RETURN -> startReturn(a);
                case LolaToolSpecs.FIND_SERVICE -> findService(a, ctx);
                case LolaToolSpecs.BOOK_SERVICE -> bookService(a);
                case LolaToolSpecs.PROCEED_TO_CHECKOUT -> proposal(call.name(), ActionProposal.of(id(),
                        ProposalType.CHECKOUT, "Go to checkout", Map.of()), "Checkout button shown");
                case LolaToolSpecs.HANDOFF_TO_SUPPORT -> handoff(a);
                default -> failure(call.name(), "Unknown tool " + call.name());
            };
        } catch (RuntimeException e) {
            LOG.warn("Lola tool {} failed: {}", call.name(), e.getClass().getSimpleName());
            outcome = failure(call.name(), "The " + call.name() + " tool is temporarily unavailable.");
        }
        metrics.toolCall(call.name(), outcome.success() ? "ok" : "error");
        return outcome;
    }

    private ToolOutcome searchProducts(JsonNode a, ToolContext ctx) {
        String query = text(a, "query");
        if (query.isBlank()) {
            return failure(LolaToolSpecs.SEARCH_PRODUCTS, "query is required");
        }
        Long maxPrice = a.has("maxPriceMinor") ? a.path("maxPriceMinor").asLong(0) : null;
        List<ProductSearch.Hit> hits = search.search(new ProductSearch.Query(query, maxPrice,
                a.path("inStockOnly").asBoolean(true), emptyToNull(text(a, "community")), settings.searchResults()),
                ctx.caller().bearerToken());
        List<ProductCard> cards = hits.stream().map(this::card).toList();
        ctx.facts().addProducts(cards);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("products", cards);
        Map<String, Object> forModel = new LinkedHashMap<>();
        forModel.put("query", query);
        forModel.put("count", cards.size());
        forModel.put("products", cards.stream().map(LolaToolkitImpl::compact).toList());
        if (cards.isEmpty()) {
            forModel.put("note", "No products matched. Do not suggest products; offer a different search.");
        }
        return new ToolOutcome(true, "products", data, quote(LolaToolSpecs.SEARCH_PRODUCTS, forModel), null, null);
    }

    private ToolOutcome compareOffers(JsonNode a, ToolContext ctx) {
        String atin = text(a, "atin").toUpperCase(Locale.ROOT);
        Optional<CatalogReader.Item> item = catalog.find(atin, ctx.caller().bearerToken());
        if (item.isEmpty()) {
            return failure(LolaToolSpecs.COMPARE_OFFERS, "No live product with ATIN " + atin);
        }
        CatalogReader.Item product = item.get();
        String title = clean(product.title());
        if (product.priceMinor() > 0 && !ctx.facts().knows(atin)) {
            ctx.facts().addProduct(new ProductCard(atin, title, "", "", product.priceMinor(), product.currency(),
                    true, 0, false, 0, 0, ""));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OfferReader.Offer o : offers.offers(product.productAtin(), ctx.caller().bearerToken())) {
            ctx.facts().addAmount(o.priceMinor());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("offerId", o.offerId());
            row.put("seller", clean(o.sellerName()));
            row.put("sellerKind", o.sellerKind());
            row.put("fulfilment", o.fulfilment());
            row.put("priceMinor", o.priceMinor());
            row.put("price", money(o.priceMinor(), o.currency()));
            row.put("inStock", o.inStock());
            row.put("deliveryDays", o.deliveryDays());
            row.put("condition", o.condition());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("atin", atin);
        data.put("title", title);
        data.put("offers", rows);
        data.put("offersAvailable", !rows.isEmpty());
        if (rows.isEmpty()) {
            data.put("note", "Seller offer comparison is not available yet; only the catalogue listing is known.");
        }
        return new ToolOutcome(true, "offers", data, quote(LolaToolSpecs.COMPARE_OFFERS, data), null, null);
    }

    private ToolOutcome checkDelivery(JsonNode a, ToolContext ctx) {
        String atin = text(a, "atin").toUpperCase(Locale.ROOT);
        String zip = text(a, "zip").isEmpty() ? Optional.ofNullable(ctx.zip()).orElse("") : text(a, "zip");
        if (!zip.matches("\\d{5}")) {
            return failure(LolaToolSpecs.CHECK_DELIVERY, "Ask the shopper for a 5-digit ZIP code first.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("atin", atin);
        data.put("zip", zip);
        Optional<DeliveryPromises.Promise> promise = promises.promise(atin, zip, ctx.caller().bearerToken());
        if (promise.isEmpty()) {
            data.put("available", false);
            data.put("note", "Delivery promises are not available yet. Do not guess a date.");
        } else {
            DeliveryPromises.Promise p = promise.get();
            data.put("available", true);
            data.put("deliverable", p.deliverable());
            data.put("label", p.label());
            data.put("earliest", p.earliest());
            data.put("latest", p.latest());
            data.put("method", p.method());
        }
        return new ToolOutcome(true, "delivery", data, quote(LolaToolSpecs.CHECK_DELIVERY, data), null, null);
    }

    private ToolOutcome addToCart(JsonNode a, ToolContext ctx) {
        String atin = text(a, "atin").toUpperCase(Locale.ROOT);
        Optional<ProductCard> known = ctx.facts().product(atin);
        if (known.isEmpty()) {
            known = catalog.find(atin, ctx.caller().bearerToken()).filter(i -> i.priceMinor() > 0)
                    .map(i -> new ProductCard(atin, clean(i.title()), "", "", i.priceMinor(), i.currency(), true, 0,
                            false, 0, 0, ""));
            known.ifPresent(ctx.facts()::addProduct);
        }
        if (known.isEmpty()) {
            return failure(LolaToolSpecs.ADD_TO_CART,
                    "Unknown product " + atin + ". Only products returned by search_products can be added.");
        }
        ProductCard card = known.get();
        if (!card.inStock()) {
            return failure(LolaToolSpecs.ADD_TO_CART, card.title() + " is out of stock.");
        }
        int quantity = Math.max(1, Math.min(a.path("quantity").asInt(1), 99));
        if (card.availableQty() > 0) {
            quantity = Math.min(quantity, card.availableQty());
        }
        ctx.facts().addAmount(card.priceMinor() * quantity);
        ActionProposal proposal = ActionProposal.of(id(), ProposalType.ADD_TO_CART,
                "Add " + quantity + " × " + card.title() + " to cart", Map.of("lines", List.of(line(card, quantity)),
                        "totalMinor", card.priceMinor() * quantity, "currency", card.currency()));
        return proposal(LolaToolSpecs.ADD_TO_CART, proposal, "Add-to-cart button shown for " + quantity + " × "
                + card.title() + ". Nothing is in the cart until the shopper taps it.");
    }

    private ToolOutcome buildCart(JsonNode a, ToolContext ctx) {
        String dish = clean(text(a, "dish"));
        int servings = a.path("servings").asInt(0);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        long total = 0;
        String currency = "USD";
        int count = 0;
        for (JsonNode ingredient : a.path("ingredients")) {
            if (++count > settings.maxRecipeItems()) {
                missing.add(text(ingredient, "name") + " (too many ingredients in one request)");
                continue;
            }
            String name = text(ingredient, "name");
            if (name.isBlank()) {
                continue;
            }
            int qty = Math.max(1, Math.min(ingredient.path("quantity").asInt(1), 99));
            Optional<ProductCard> match = search.search(new ProductSearch.Query(name, null, true, null, 3),
                    ctx.caller().bearerToken()).stream().filter(ProductSearch.Hit::inStock).findFirst().map(this::card);
            if (match.isEmpty()) {
                missing.add(name);
                continue;
            }
            ProductCard card = match.get();
            if (card.availableQty() > 0) {
                qty = Math.min(qty, card.availableQty());
            }
            ctx.facts().addProduct(card);
            total += card.priceMinor() * qty;
            currency = card.currency();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ingredient", name);
            item.put("quantity", qty);
            item.put("product", card);
            items.add(item);
            lines.add(line(card, qty));
        }
        ctx.facts().addAmount(total);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dish", dish);
        data.put("servings", servings);
        data.put("items", items);
        data.put("missing", missing);
        data.put("totalMinor", total);
        data.put("total", money(total, currency));
        data.put("currency", currency);
        ActionProposal proposal = lines.isEmpty() ? null : ActionProposal.of(id(), ProposalType.ADD_TO_CART,
                "Add all to cart", Map.of("dish", dish, "servings", servings, "lines", lines, "totalMinor", total,
                        "currency", currency));
        Map<String, Object> forModel = new LinkedHashMap<>(data);
        forModel.put("items", items.stream().map(i -> Map.of("ingredient", i.get("ingredient"), "quantity",
                i.get("quantity"), "product", compact((ProductCard) i.get("product")))).toList());
        forModel.put("note", "An 'Add all to cart' button is shown; nothing is in the cart until the shopper taps it."
                + (missing.isEmpty() ? "" : " Tell the shopper which ingredients were not found."));
        return new ToolOutcome(true, "recipe", data, quote(LolaToolSpecs.BUILD_CART_FROM_RECIPE, forModel), proposal,
                null);
    }

    private ToolOutcome trackOrder(JsonNode a, ToolContext ctx) {
        String orderId = text(a, "orderId");
        if (orderId.isBlank()) {
            return failure(LolaToolSpecs.TRACK_ORDER, "Ask the shopper for their order number.");
        }
        OrderTracker.Lookup lookup = orders.track(orderId, ctx.caller().bearerToken());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("availability", lookup.availability().name());
        switch (lookup.availability()) {
            case FOUND -> {
                data.put("status", lookup.status());
                data.put("shipments", lookup.shipments());
            }
            case NOT_FOUND -> data.put("note", "No order with that number on this account.");
            case UNAVAILABLE -> data.put("note", "Order tracking is not available yet; offer a hand-off to support.");
            default -> throw new IllegalStateException("unexpected availability");
        }
        return new ToolOutcome(lookup.availability() != OrderTracker.Availability.UNAVAILABLE, "order", data,
                quote(LolaToolSpecs.TRACK_ORDER, data), null, null);
    }

    private ToolOutcome myOrders(JsonNode a, ToolContext ctx) {
        int limit = Math.max(1, Math.min(10, a.path("limit").asInt(5)));
        AccountReader.Result<List<AccountReader.OrderSummary>> r = account.recentOrders(ctx.caller().bearerToken(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        if (!r.available()) {
            data.put("note", "Order history is not available yet; offer a hand-off to support.");
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AccountReader.OrderSummary o : r.value()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderNumber", o.orderNumber());
                m.put("status", o.status());
                m.put("placed", o.placedAt());
                m.put("total", money(o.totalMinor(), o.currency()));
                m.put("shipments", o.shipments());
                rows.add(m);
            }
            data.put("orders", rows);
            if (rows.isEmpty()) {
                data.put("note", "The shopper has no orders yet.");
            }
        }
        return new ToolOutcome(r.available(), "orders", data, quote(LolaToolSpecs.MY_ORDERS, data), null, null);
    }

    private ToolOutcome viewCart(ToolContext ctx) {
        AccountReader.Result<AccountReader.CartSummary> r = account.cart(ctx.caller().bearerToken());
        Map<String, Object> data = new LinkedHashMap<>();
        if (!r.available()) {
            data.put("note", "The cart is not available right now.");
        } else {
            AccountReader.CartSummary c = r.value();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AccountReader.CartLine l : c.lines()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("atin", l.atin());
                m.put("title", clean(l.title()));
                m.put("quantity", l.quantity());
                m.put("price", money(l.priceMinor(), c.currency()));
                if (l.unavailable()) {
                    m.put("warning", "unavailable: left out of checkout");
                } else if (l.priceChanged()) {
                    m.put("warning", "the price changed since it was added");
                }
                rows.add(m);
            }
            data.put("items", rows);
            data.put("itemCount", c.itemCount());
            data.put("subtotal", money(c.subtotalMinor(), c.currency()));
            data.put("readyForCheckout", c.checkoutable());
            if (rows.isEmpty()) {
                data.put("note", "The cart is empty.");
            }
        }
        return new ToolOutcome(r.available(), "cart", data, quote(LolaToolSpecs.VIEW_CART, data), null, null);
    }

    private ToolOutcome myBookings(ToolContext ctx) {
        AccountReader.Result<List<AccountReader.BookingSummary>> r = account.bookings(ctx.caller().bearerToken());
        Map<String, Object> data = new LinkedHashMap<>();
        if (!r.available()) {
            data.put("note", "Bookings are not available yet; offer a hand-off to support.");
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AccountReader.BookingSummary b : r.value()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reference", b.reference());
                m.put("service", clean(b.service()));
                m.put("status", b.status());
                m.put("start", b.startAt());
                m.put("price", money(b.priceMinor(), "USD"));
                m.put("deposit", money(b.depositMinor(), "USD"));
                rows.add(m);
            }
            data.put("bookings", rows);
            if (rows.isEmpty()) {
                data.put("note", "The shopper has no bookings.");
            }
        }
        return new ToolOutcome(r.available(), "bookings", data, quote(LolaToolSpecs.MY_BOOKINGS, data), null, null);
    }

    private ToolOutcome startReturn(JsonNode a) {
        String orderId = text(a, "orderId");
        String reason = text(a, "reason").isEmpty() ? "OTHER" : text(a, "reason").toUpperCase(Locale.ROOT);
        List<String> items = new ArrayList<>();
        a.path("items").forEach(n -> items.add(n.asString("").toUpperCase(Locale.ROOT)));
        ActionProposal proposal = ActionProposal.of(id(), ProposalType.START_RETURN,
                "Start a return for order " + orderId, Map.of("orderId", orderId, "reason", reason, "items", items));
        return proposal(LolaToolSpecs.START_RETURN, proposal,
                "Return button shown; the return is only created when the shopper taps it.");
    }

    private ToolOutcome findService(JsonNode a, ToolContext ctx) {
        String query = text(a, "query");
        String zip = text(a, "zip").isEmpty() ? Optional.ofNullable(ctx.zip()).orElse("") : text(a, "zip");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServiceDirectory.Listing l : services.find(query, emptyToNull(zip), ctx.caller().bearerToken())) {
            ctx.facts().addAmount(l.priceFromMinor());
            ctx.facts().addAmount(l.depositMinor());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serviceId", l.serviceId());
            row.put("name", clean(l.name()));
            row.put("provider", clean(l.providerName()));
            row.put("description", clean(l.description()));
            row.put("priceFromMinor", l.priceFromMinor());
            row.put("priceFrom", money(l.priceFromMinor(), l.currency()));
            row.put("depositMinor", l.depositMinor());
            row.put("rating", l.rating());
            row.put("nextAvailable", l.nextAvailable());
            row.put("location", clean(l.location()));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("services", rows);
        if (rows.isEmpty()) {
            data.put("note", "No services found (or service search is not available yet). Do not invent providers.");
        }
        return new ToolOutcome(true, "services", data, quote(LolaToolSpecs.FIND_SERVICE, data), null, null);
    }

    private ToolOutcome bookService(JsonNode a) {
        String serviceId = text(a, "serviceId");
        if (serviceId.isBlank()) {
            return failure(LolaToolSpecs.BOOK_SERVICE, "Use a serviceId returned by find_service.");
        }
        ActionProposal proposal = ActionProposal.of(id(), ProposalType.BOOK_SERVICE, "Book this service",
                Map.of("serviceId", serviceId, "preferredTime", text(a, "preferredTime"), "notes",
                        clean(text(a, "notes"))));
        return proposal(LolaToolSpecs.BOOK_SERVICE, proposal,
                "Booking button shown; nothing is booked or paid until the shopper taps it.");
    }

    private ToolOutcome handoff(JsonNode a) {
        ToolOutcome.HandoffRequest request = new ToolOutcome.HandoffRequest(
                text(a, "reason").isEmpty() ? "assistant-escalation" : text(a, "reason"), text(a, "summary"));
        Map<String, Object> data = Map.of("reason", request.reason());
        return new ToolOutcome(true, "handoff", data, quote(LolaToolSpecs.HANDOFF_TO_SUPPORT,
                Map.of("status", "Support has been notified; tell the shopper an agent will join shortly.")), null,
                request);
    }

    private ToolOutcome proposal(String tool, ActionProposal proposal, String note) {
        return new ToolOutcome(true, "proposal", proposal, quote(tool, Map.of("status", "PROPOSED",
                "proposalId", proposal.id(), "note", note)), proposal, null);
    }

    private ToolOutcome failure(String tool, String message) {
        return new ToolOutcome(false, "error", Map.of("message", message), quote(tool, Map.of("error", message)),
                null, null);
    }

    private ProductCard card(ProductSearch.Hit h) {
        return new ProductCard(h.atin(), clean(h.title()), clean(h.brand()), clean(h.sellerName()), h.priceMinor(),
                h.currency(), h.inStock(), h.availableQty(), h.lowStock(), h.deliveryDays(), h.rating(), h.imageUrl());
    }

    private static Map<String, Object> compact(ProductCard c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("atin", c.atin());
        m.put("title", c.title());
        m.put("brand", c.brand());
        m.put("seller", c.sellerName());
        m.put("price", money(c.priceMinor(), c.currency()));
        m.put("priceMinor", c.priceMinor());
        m.put("inStock", c.inStock());
        m.put("lowStock", c.lowStock());
        m.put("deliveryDays", c.deliveryDays());
        m.put("rating", c.rating());
        return m;
    }

    private static Map<String, Object> line(ProductCard card, int quantity) {
        return Map.of("variantAtin", card.atin(), "quantity", quantity, "title", card.title(), "priceMinor",
                card.priceMinor(), "currency", card.currency());
    }

    /** "$4.99" style amount written exactly as the grounding verifier parses it. */
    static String money(long minor, String currency) {
        String symbol = switch (currency == null ? "USD" : currency.toUpperCase(Locale.ROOT)) {
            case "USD" -> "$";
            case "GBP" -> "£";
            case "EUR" -> "€";
            case "NGN" -> "₦";
            default -> currency + " ";
        };
        return symbol + (minor / 100) + "." + String.format(Locale.ROOT, "%02d", Math.abs(minor % 100));
    }

    private String clean(String sellerText) {
        InjectionFilter.FilteredText f = injection.sanitize(sellerText, MAX_SELLER_TEXT);
        if (f.removed() > 0) {
            metrics.guardrail("injection_removed", f.removed());
        }
        return f.text();
    }

    private String quote(String tool, Object payload) {
        return injection.quote("tool:" + tool, json.writeValueAsString(payload));
    }

    private JsonNode args(ToolCallRequest call) {
        try {
            return json.readTree(call.arguments());
        } catch (JacksonException e) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("").trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String id() {
        return "prop_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
