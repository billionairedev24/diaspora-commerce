package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.orders.domain.BusinessDays;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingPolicy;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.HandoverCodeVault;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.OrderCouponRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.PromiseClient;
import com.afrotransact.orders.port.TaxCalculator;
import com.afrotransact.orders.port.UpstreamFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Activities of the checkout saga, calling the upstream ports and the order placement use case. */
@Component
@ActivityImpl(taskQueues = CheckoutWorkflow.TASK_QUEUE)
class CheckoutActivitiesImpl implements CheckoutActivities {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutActivitiesImpl.class);

    private final MarketEligibility markets;
    private final ProductFactsClient catalog;
    private final PromiseClient promises;
    private final TaxCalculator tax;
    private final CheckoutTax checkoutTax;
    private final ShippingPolicy shipping;
    private final HandoverPolicy handover;
    private final HandoverCodeVault vault;
    private final InventoryClient inventory;
    private final PaymentsClient payments;
    private final CartClient cart;
    private final CouponsClient coupons;
    private final OrderCouponRepository orderCoupons;
    private final OrderRepository orderRepository;
    private final OrderPlacementService placement;
    private final LocalDeliveryQuotes localDelivery;
    private final OrdersSettings settings;
    private final Clock clock;

    CheckoutActivitiesImpl(MarketEligibility markets, ProductFactsClient catalog, PromiseClient promises,
                           TaxCalculator tax, CheckoutTax checkoutTax, ShippingPolicy shipping,
                           HandoverPolicy handover, HandoverCodeVault vault, InventoryClient inventory,
                           PaymentsClient payments, CartClient cart, CouponsClient coupons,
                           OrderCouponRepository orderCoupons, OrderRepository orderRepository,
                           OrderPlacementService placement, LocalDeliveryQuotes localDelivery,
                           OrdersSettings settings, Clock clock) {
        this.markets = markets;
        this.catalog = catalog;
        this.promises = promises;
        this.tax = tax;
        this.checkoutTax = checkoutTax;
        this.shipping = shipping;
        this.handover = handover;
        this.vault = vault;
        this.inventory = inventory;
        this.payments = payments;
        this.cart = cart;
        this.coupons = coupons;
        this.orderCoupons = orderCoupons;
        this.orderRepository = orderRepository;
        this.placement = placement;
        this.localDelivery = localDelivery;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ validate + price + tax

    @Override
    public CheckoutPlan prepare(CheckoutInput in) {
        return guard(() -> plan(in));
    }

    private CheckoutPlan plan(CheckoutInput in) {
        List<CartClient.Item> items = in.items();
        if (items.isEmpty()) {
            throw new OrdersRuleViolation(CheckoutCodes.CART_EMPTY, "The cart has nothing to check out");
        }
        List<String> unavailable = items.stream().filter(CartClient.Item::unavailable)
                .map(CartClient.Item::variantAtin).toList();
        if (!unavailable.isEmpty()) {
            throw ApplicationFailure.newNonRetryableFailure("Some items can no longer be bought: "
                    + String.join(", ", unavailable), CheckoutCodes.CART_NOT_CHECKOUTABLE, String.join("\n",
                    unavailable));
        }
        if (items.stream().anyMatch(i -> i.currency() != null && !i.currency().equalsIgnoreCase(in.currency()))) {
            throw new OrdersRuleViolation(CheckoutCodes.CURRENCY_MISMATCH, "All items must be in " + in.currency());
        }
        ShipTo to = in.shipTo();
        markets.check(to.country(), to.state(), to.postalCode()).ifPresent(r -> {
            if (!r.deliveryAllowed()) {
                throw new OrdersRuleViolation(CheckoutCodes.MARKET_DISABLED, r.message() != null ? r.message()
                        : "We don't deliver to " + to.summary() + " yet");
            }
        });
        Map<String, ProductFactsClient.Facts> facts = catalog.facts(items.stream()
                .map(CartClient.Item::variantAtin).distinct().toList());
        TaxCalculator.Rate rate = tax.rateFor(to.country(), to.state(), to.postalCode());
        Map<String, Integer> promised = promises.days(to.postalCode(), items.stream()
                .map(i -> new PromiseClient.Item(i.variantAtin(), i.fulfilment(), i.sellerOrgId())).toList());

        // S255: AfroTransact Delivery offers are collected at the seller's pickup location; one package per location.
        Map<String, PickupLocations.PickupLocation> pickups = new HashMap<>();
        for (CartClient.Item i : items) {
            if (fromSellerStore(i)) {
                pickups.computeIfAbsent(offerKey(i), k -> localDelivery.pickupFor(i));
            }
        }
        Map<String, List<CartClient.Item>> byPackage = new LinkedHashMap<>();
        for (CartClient.Item i : items) {
            String key = fromSellerStore(i) ? packageId(i) + ":" + pickups.get(offerKey(i)).locationId()
                    : packageId(i);
            byPackage.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        Map<String, CheckoutInput.Selection> selections = new LinkedHashMap<>();
        for (CheckoutInput.Selection s : in.selections()) {
            if (s.packageId() != null) {
                selections.put(s.packageId(), s);
            }
        }
        List<CheckoutPlan.Package> packages = new ArrayList<>();
        int lineNo = 0;
        for (Map.Entry<String, List<CartClient.Item>> e : byPackage.entrySet()) {
            CartClient.Item first = e.getValue().getFirst();
            boolean sellerStore = fromSellerStore(first);
            // Clients key selections by the cart's package (seller + fulfilment); a store's package inherits it.
            CheckoutInput.Selection sel = selections.getOrDefault(e.getKey(), selections.get(packageId(first)));
            DeliveryMethod method = method(sel);
            if (sellerStore) {
                if (sel != null && sel.option() != null && !sel.option().isBlank()
                        && method != DeliveryMethod.AFROTRANSACT_DELIVERY) {
                    throw new OrdersRuleViolation(CheckoutCodes.INVALID_SELECTION, (first.sellerName() == null
                            ? first.sellerOrgId() : first.sellerName()) + " delivers this package with AfroTransact "
                            + "Delivery from its store; choose afrotransact_delivery");
                }
                method = DeliveryMethod.AFROTRANSACT_DELIVERY;
            }
            String level = sel != null && sel.serviceLevel() != null && !sel.serviceLevel().isBlank()
                    ? sel.serviceLevel().trim().toLowerCase(Locale.ROOT)
                    : sel != null && "express".equalsIgnoreCase(sel.option()) ? "express"
                    : shipping.defaultServiceLevel(method);
            String pickup = sel == null ? null : sel.pickupLocationId();
            if (method == DeliveryMethod.PICKUP && (pickup == null || pickup.isBlank())) {
                throw new OrdersRuleViolation(CheckoutCodes.INVALID_SELECTION, "Choose a pickup location for "
                        + (first.sellerName() == null ? e.getKey() : first.sellerName()));
            }
            DeliveryPreferences prefs = sel == null || sel.preferences() == null ? DeliveryPreferences.defaults()
                    : sel.preferences();
            int fallback = shipping.estimatedDays(method, level);
            List<CheckoutPlan.Line> lines = new ArrayList<>();
            int days = 0;
            for (CartClient.Item i : e.getValue()) {
                lineNo++;
                Optional<ProductFactsClient.Facts> f = Optional.ofNullable(facts.get(i.variantAtin()));
                int lineDays = method == DeliveryMethod.CARRIER && promised.containsKey(i.variantAtin())
                        ? Math.max(1, promised.get(i.variantAtin())) : fallback;
                days = Math.max(days, lineDays);
                long itemsMinor = Math.multiplyExact(i.priceMinor(), (long) i.quantity());
                lines.add(new CheckoutPlan.Line(UUID.randomUUID().toString(), lineNo, i.variantAtin(),
                        i.productAtin(), i.title(), i.imageUrl(), i.quantity(), i.priceMinor(),
                        rate.taxOf(itemsMinor), f.map(ProductFactsClient.Facts::category).orElse(null),
                        f.map(ProductFactsClient.Facts::perishable).orElse(false), rate.jurisdiction(), lineDays,
                        i.offerId(), 0, f.map(ProductFactsClient.Facts::productType).orElse(null), null, 0));
            }
            LocalDelivery quoted = sellerStore ? localDelivery.quote(first.sellerOrgId(), first.sellerName(),
                    pickups.get(offerKey(first)), to) : null;
            packages.add(new CheckoutPlan.Package(e.getKey(), first.sellerOrgId(), first.sellerName(),
                    fulfilmentType(first), method, level, method == DeliveryMethod.PICKUP ? pickup.trim() : null,
                    prefs, days, lines, quoted));
        }
        return new CheckoutPlan(in.currency(), packages);
    }

    // ------------------------------------------------------------------ reserve + split

    @Override
    public OrderDraft reserve(CheckoutInput in, CheckoutPlan plan) {
        return guard(() -> draft(in, plan));
    }

    private OrderDraft draft(CheckoutInput in, CheckoutPlan plan) {
        List<InventoryClient.Item> tracked = new ArrayList<>();
        for (CheckoutPlan.Package p : plan.packages()) {
            if (p.fulfilmentType().inventoryTracked()) {
                p.lines().forEach(l -> tracked.add(new InventoryClient.Item(l.variantAtin(), l.quantity(),
                        l.lineId())));
            }
        }
        Map<String, String> nodeBySku = new LinkedHashMap<>();
        Map<String, String> nodeByLine = new LinkedHashMap<>();
        boolean reserved = false;
        if (!tracked.isEmpty()) {
            // One reservation line per order line, grouped by the line id: a shipment's lines can then be shipped
            // or released on their own (inventory /groups/commit and /groups/release).
            List<InventoryClient.Reserved> held = inventory.reserve(in.orderId(), tracked);
            held.forEach(r -> {
                nodeBySku.putIfAbsent(r.sku().toUpperCase(Locale.ROOT), r.nodeId());
                if (r.group() != null) {
                    nodeByLine.put(r.group(), r.nodeId());
                }
            });
            reserved = true;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(settings.zone())));
        List<OrderDraft.DraftShipment> shipments = new ArrayList<>();
        int seq = 0;
        for (CheckoutPlan.Package p : plan.packages()) {
            Map<String, List<CheckoutPlan.Line>> byNode = new LinkedHashMap<>();
            for (CheckoutPlan.Line l : p.lines()) {
                String node = p.fulfilmentType().inventoryTracked() ? nodeByLine.getOrDefault(l.lineId(),
                        nodeBySku.getOrDefault(l.variantAtin().toUpperCase(Locale.ROOT), "")) : "";
                byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(l);
            }
            for (Map.Entry<String, List<CheckoutPlan.Line>> group : byNode.entrySet()) {
                seq++;
                String id = UUID.randomUUID().toString();
                List<CheckoutPlan.Line> lines = group.getValue();
                long items = lines.stream().mapToLong(l -> Math.multiplyExact(l.unitPriceMinor(), (long) l.quantity()))
                        .sum();
                boolean perishable = lines.stream().anyMatch(CheckoutPlan.Line::perishable);
                boolean codeNeeded = handover.required(perishable, p.method(), p.preferences());
                String hash = null;
                String sealed = null;
                if (codeNeeded) {
                    String code = handover.newCode();
                    hash = handover.hash(id, code);
                    sealed = vault.seal(id, code);
                }
                int days = lines.stream().mapToInt(CheckoutPlan.Line::promiseDays).max().orElse(p.promiseDays());
                int slack = p.method() == DeliveryMethod.CARRIER ? 2 : 0;
                shipments.add(new OrderDraft.DraftShipment(id, seq, p.sellerOrgId(), p.sellerName(),
                        p.fulfilmentType(), group.getKey().isEmpty() ? null : group.getKey(), p.method(),
                        p.serviceLevel(), p.pickupLocationId(), p.preferences(), perishable, codeNeeded, hash, sealed,
                        shippingOf(p, items), BusinessDays.add(today, days), BusinessDays.add(today, days + slack),
                        lines, p.localDelivery()));
            }
        }
        return checkoutTax.tax(new OrderDraft(in.orderId(), in.orderNumber(), in.customerId(), in.workflowId(),
                plan.currency(), in.shipTo(), reserved, shipments, Instant.now(clock), null));
    }

    // ------------------------------------------------------------------ coupons (offers-service)

    @Override
    public void validateCoupon(CheckoutInput in, CheckoutPlan plan) {
        guard(() -> {
            long shippingEstimate = 0;
            List<CouponsClient.Line> lines = new ArrayList<>();
            for (CheckoutPlan.Package p : plan.packages()) {
                long items = p.lines().stream().mapToLong(CheckoutPlan.Line::itemsMinor).sum();
                shippingEstimate += shippingOf(p, items);
                p.lines().stream().filter(l -> l.offerId() != null)
                        .forEach(l -> lines.add(new CouponsClient.Line(l.offerId(), l.quantity())));
            }
            CouponsClient.Quote quote = coupons.validate(new CouponsClient.Request(in.couponCode(), in.customerId(),
                    null, in.currency(), lines, shippingEstimate, firstOrder(in)));
            if (!quote.applicable()) {
                throw ApplicationFailure.newNonRetryableFailure(quote.message() != null ? quote.message()
                        : "Coupon " + in.couponCode() + " can't be used on this order",
                        CheckoutCodes.COUPON_NOT_APPLICABLE, quote.rejection() == null ? "" : quote.rejection());
            }
            return null;
        });
    }

    @Override
    public OrderDraft redeemCoupon(CheckoutInput in, OrderDraft draft) {
        return guard(() -> {
            CouponsClient.Redemption r;
            try {
                r = coupons.redeem(in.orderId(), couponRequest(in, draft));
            } catch (UpstreamFailure f) {
                if (f.status() == 422 || "offers.coupon-not-applicable".equals(f.code())) {
                    throw ApplicationFailure.newNonRetryableFailure(f.getMessage(),
                            CheckoutCodes.COUPON_NOT_APPLICABLE, f.code());
                }
                throw f;
            }
            // Discounts change the taxable amounts (and shipping discounts the taxable shipping): quote again.
            return checkoutTax.tax(applyCoupon(draft, in.couponCode(), r));
        });
    }

    @Override
    public void releaseCoupon(CheckoutInput in, OrderDraft draft, String redemptionId) {
        guard(() -> {
            String id = redemptionId;
            if (id == null) {
                try {
                    id = coupons.redeem(in.orderId(), couponRequest(in, draft)).id();
                } catch (UpstreamFailure f) {
                    if (f.retryable()) {
                        throw f;
                    }
                    LOG.debug("No coupon redemption to release for order {} ({})", in.orderId(), f.code());
                    return null;
                }
            }
            try {
                coupons.release(id);
            } catch (UpstreamFailure f) {
                if (f.status() != 404) {
                    throw f;
                }
            }
            orderCoupons.markReleased(in.orderId(), Instant.now(clock));
            return null;
        });
    }

    private CouponsClient.Request couponRequest(CheckoutInput in, OrderDraft draft) {
        List<CouponsClient.Line> lines = draft.shipments().stream().flatMap(s -> s.lines().stream())
                .filter(l -> l.offerId() != null).map(l -> new CouponsClient.Line(l.offerId(), l.quantity()))
                .toList();
        return new CouponsClient.Request(in.couponCode(), in.customerId(), in.orderId(), draft.currency(), lines,
                draft.shippingMinor(), firstOrder(in));
    }

    /** True when the shopper has no earlier placed order (FIRST_ORDER coupons). */
    private boolean firstOrder(CheckoutInput in) {
        return orderRepository.byCustomer(in.customerId(), 0, 5).items().stream()
                .noneMatch(o -> !o.id().equals(in.orderId()) && o.placedAt() != null);
    }

    /** Line discounts by offer id; the shipping discount comes off the shipments' shipping in order. */
    static OrderDraft applyCoupon(OrderDraft draft, String code, CouponsClient.Redemption r) {
        Map<String, Long> byOffer = new LinkedHashMap<>();
        r.lines().forEach(l -> byOffer.merge(l.lineRef(), l.discountMinor(), Long::sum));
        long shippingLeft = r.shippingDiscountMinor();
        List<OrderDraft.DraftShipment> out = new ArrayList<>();
        for (OrderDraft.DraftShipment s : draft.shipments()) {
            List<CheckoutPlan.Line> lines = new ArrayList<>();
            for (CheckoutPlan.Line l : s.lines()) {
                Long d = l.offerId() == null ? null : byOffer.remove(l.offerId());
                lines.add(d == null ? l : l.withDiscount(d));
            }
            long off = Math.min(shippingLeft, s.shippingMinor());
            shippingLeft -= off;
            out.add(s.with(lines, s.shippingMinor() - off));
        }
        return draft.withCoupon(out, new OrderDraft.CouponRef(code, r.id(), r.discountMinor(),
                r.shippingDiscountMinor() - shippingLeft, r.funding(), r.fundedByOrg()));
    }

    // ------------------------------------------------------------------ payment + persistence

    @Override
    public PaymentRef createPayment(OrderDraft draft) {
        return guard(() -> {
            Order order = OrderDrafts.toOrder(draft, null);
            PaymentsClient.Intent intent = payments.createIntent(new PaymentsClient.NewIntent(order.id(),
                    order.totals().grandTotalMinor(), order.currency(), Instant.now(clock),
                    Map.of("order_number", order.number(), "customer_id", order.customerId()),
                    PaymentLines.of(order)));
            return new PaymentRef(intent.paymentId(), intent.clientSecret(), intent.status());
        });
    }

    @Override
    public void persistPending(OrderDraft draft, PaymentRef payment) {
        guard(() -> {
            placement.persistPending(OrderDrafts.toOrder(draft, payment.paymentId()), payment.clientSecret());
            OrderDraft.CouponRef c = draft.coupon();
            if (c != null) {
                orderCoupons.save(new OrderCouponRepository.OrderCoupon(draft.orderId(), c.code(), c.redemptionId(),
                        c.discountMinor(), c.shippingDiscountMinor(), c.funding(), c.fundedByOrg(), "REDEEMED",
                        Instant.now(clock), null));
            }
            return null;
        });
    }

    @Override
    public void place(String orderId, boolean reserved) {
        guard(() -> {
            if (reserved) {
                inventory.confirm(orderId);
            }
            placement.place(orderId);
            return null;
        });
    }

    @Override
    public void clearCart(String customerId, List<String> variantAtins) {
        guard(() -> {
            cart.clearOrdered(customerId, variantAtins);
            return null;
        });
    }

    // ------------------------------------------------------------------ compensations

    @Override
    public void releaseReservation(String orderId, String reason) {
        guard(() -> {
            try {
                inventory.release(orderId, reason);
            } catch (UpstreamFailure f) {
                if (f.status() != 404) {
                    throw f;
                }
                LOG.debug("No reservation to release for order {}", orderId);
            }
            return null;
        });
    }

    @Override
    public void cancelPayment(String orderId, String paymentId, String reason) {
        guard(() -> {
            String id = paymentId != null ? paymentId
                    : payments.findByOrder(orderId).map(PaymentsClient.Intent::paymentId).orElse(null);
            if (id == null) {
                return null;
            }
            try {
                payments.cancel(id, reason);
            } catch (UpstreamFailure f) {
                if (f.status() != 404 && f.status() != 409) {
                    throw f;
                }
                LOG.info("Payment {} of order {} not cancellable ({}); leaving it", id, orderId, f.code());
            }
            return null;
        });
    }

    @Override
    public void abandon(String orderId, String code, String message) {
        guard(() -> {
            placement.abandon(orderId, code, message);
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The quoted AfroTransact Delivery charge for a seller's store (S255), else the shipping policy's fee. */
    private long shippingOf(CheckoutPlan.Package p, long itemsMinor) {
        return p.localDelivery() != null ? p.localDelivery().customerChargeMinor()
                : shipping.fee(p.method(), p.serviceLevel(), itemsMinor);
    }

    private static boolean fromSellerStore(CartClient.Item i) {
        return LocalDeliveryQuotes.FULFILMENT.equalsIgnoreCase(i.fulfilment());
    }

    private static String offerKey(CartClient.Item i) {
        return i.offerId() == null ? "" : i.offerId();
    }

    private String packageId(CartClient.Item i) {
        return i.sellerOrgId() + ":" + (i.fulfilment() == null ? "SELLER" : i.fulfilment().toUpperCase(Locale.ROOT));
    }

    private FulfilmentType fulfilmentType(CartClient.Item i) {
        if (i.fulfilment() == null || "SELLER".equalsIgnoreCase(i.fulfilment()) || fromSellerStore(i)) {
            return FulfilmentType.SELLER_FULFILLED;
        }
        boolean platform = "PLATFORM".equalsIgnoreCase(i.sellerKind())
                || settings.retailOrgId().equals(i.sellerOrgId());
        return platform ? FulfilmentType.RETAIL_1P : FulfilmentType.FBA_AFROTRANSACT;
    }

    private static DeliveryMethod method(CheckoutInput.Selection sel) {
        String option = sel == null || sel.option() == null ? "carrier" : sel.option().trim().toLowerCase(Locale.ROOT);
        return switch (option) {
            case "carrier", "standard", "express", "ship" -> DeliveryMethod.CARRIER;
            case "afrotransact_delivery", "delivery", "local" -> DeliveryMethod.AFROTRANSACT_DELIVERY;
            case "pickup" -> DeliveryMethod.PICKUP;
            default -> throw new OrdersRuleViolation(CheckoutCodes.INVALID_SELECTION,
                    "Unknown delivery option " + sel.option());
        };
    }

    /** Maps business refusals to non-retryable failures typed with the checkout code; outages stay retryable. */
    private static <T> T guard(Supplier<T> step) {
        try {
            return step.get();
        } catch (OrdersRuleViolation e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.code());
        } catch (OrdersStateConflict e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.code());
        } catch (UpstreamFailure f) {
            if ("inventory.insufficient-stock".equals(f.code())) {
                throw ApplicationFailure.newNonRetryableFailure("Not enough stock for some items",
                        CheckoutCodes.INSUFFICIENT_STOCK, String.join("\n", f.details()));
            }
            if ("inventory.reservation-expired".equals(f.code())) {
                throw ApplicationFailure.newNonRetryableFailure(f.getMessage(), CheckoutCodes.RESERVATION_EXPIRED);
            }
            if (f.retryable()) {
                throw ApplicationFailure.newFailure(f.service() + " unavailable: " + f.getMessage(),
                        CheckoutCodes.UPSTREAM_UNAVAILABLE);
            }
            throw ApplicationFailure.newNonRetryableFailure(f.service() + " refused the request (" + f.code() + "): "
                    + f.getMessage(), CheckoutCodes.UPSTREAM_REJECTED);
        }
    }
}
