package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.payments.api.OrderPaymentsApi;

/**
 * Stand-ins for the published APIs of the modules orders calls at checkout, with the state that matters to the saga (stock held and
 * on hand, PaymentIntents, coupon redemptions) and a fault switch per operation. They are called through the REAL in-process adapters
 * of orders ({@code InProcessWiring}), so every call arrives as orders' caller principal exactly as in core; each fake records the
 * principal it was entered with. A fault is a {@link RuntimeException} thrown by the called module: a {@link ModuleApiException} is a
 * refusal its advice would answer with a status, anything else is the module crashing.
 */
final class InProcessCallees {

    private InProcessCallees() {
    }

    /** Operation-level fault injection: {@code failing("reserve", ex, 2)} fails the next two calls of {@code reserve}. */
    static class Faults {

        private final Map<String, Supplier<RuntimeException>> faults = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final List<String> principals = new CopyOnWriteArrayList<>();

        void failing(String op, Supplier<RuntimeException> fault) {
            failing(op, fault, Integer.MAX_VALUE);
        }

        void failing(String op, Supplier<RuntimeException> fault, int times) {
            faults.put(op, fault);
            remaining.put(op, new AtomicInteger(times));
        }

        int calls(String op) {
            AtomicInteger n = calls.get(op);
            return n == null ? 0 : n.get();
        }

        /** Counts the call, records the principal and throws the injected fault while any is left. */
        void enter(String op) {
            calls.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet();
            principals.add(InProcessCalls.require().clientId());
            Supplier<RuntimeException> fault = faults.get(op);
            if (fault != null && remaining.get(op).getAndDecrement() > 0) {
                throw fault.get();
            }
        }
    }

    /** Inventory: stock on hand per SKU, holds per order; one hold per order (idempotent), all lines or none. */
    static final class Stock implements ReservationApi {

        final Faults faults = new Faults();
        private final Map<String, Integer> onHand = new LinkedHashMap<>();
        private final Map<String, Hold> holds = new LinkedHashMap<>();

        record Hold(List<Line> lines, String status) {
        }

        Stock with(String sku, int qty) {
            onHand.put(sku, qty);
            return this;
        }

        /** Units neither held nor committed. */
        synchronized int available(String sku) {
            int held = holds.values().stream().filter(h -> "HELD".equals(h.status()) || "CONFIRMED".equals(h.status()))
                    .flatMap(h -> h.lines().stream()).filter(l -> l.sku().equals(sku)).mapToInt(Line::qty).sum();
            return onHand.getOrDefault(sku, 0) - held;
        }

        /** The hold's TTL ran out inside inventory (no caller involved). */
        synchronized void expire(String orderId) {
            Hold h = holds.get(orderId);
            holds.put(orderId, new Hold(h.lines(), "RELEASED"));
        }

        synchronized String status(String orderId) {
            Hold h = holds.get(orderId);
            return h == null ? null : h.status();
        }

        @Override
        public synchronized ReservationInfo reserve(String orderId, String idempotencyKey, List<Line> lines) {
            faults.enter("reserve");
            if (!holds.containsKey(orderId)) {
                List<String> short_ = new ArrayList<>();
                for (Line l : lines) {
                    if (available(l.sku()) < l.qty()) {
                        short_.add(l.sku() + ": requested " + l.qty() + ", available " + available(l.sku()));
                    }
                }
                if (!short_.isEmpty()) {
                    throw new ModuleApiException(409, "inventory.insufficient-stock", "Insufficient stock", short_, null);
                }
                holds.put(orderId, new Hold(List.copyOf(lines), "HELD"));
            }
            return info(orderId, holds.get(orderId));
        }

        @Override
        public synchronized Optional<ReservationInfo> find(String orderId) {
            faults.enter("find");
            return Optional.ofNullable(holds.get(orderId)).map(h -> info(orderId, h));
        }

        @Override
        public synchronized ReservationInfo confirm(String orderId) {
            faults.enter("confirm");
            Hold h = require(orderId);
            if ("RELEASED".equals(h.status())) {
                throw new ModuleApiException(409, "inventory.reservation-expired", "The hold for " + orderId + " expired");
            }
            holds.put(orderId, new Hold(h.lines(), "CONFIRMED"));
            return info(orderId, holds.get(orderId));
        }

        @Override
        public synchronized void commit(String orderId) {
            faults.enter("commit");
            require(orderId);
        }

        @Override
        public synchronized ReservationInfo release(String orderId, String reason) {
            faults.enter("release");
            Hold h = require(orderId);
            holds.put(orderId, new Hold(h.lines(), "RELEASED"));
            return info(orderId, holds.get(orderId));
        }

        @Override
        public void commitGroups(String orderId, Set<String> groups) {
            faults.enter("commitGroups");
        }

        @Override
        public ReservationInfo releaseGroups(String orderId, Set<String> groups, String reason) {
            faults.enter("releaseGroups");
            return null;
        }

        private Hold require(String orderId) {
            Hold h = holds.get(orderId);
            if (h == null) {
                throw new ModuleApiException(404, "inventory.reservation-not-found", "No reservation for " + orderId);
            }
            return h;
        }

        private static ReservationInfo info(String orderId, Hold h) {
            return new ReservationInfo(orderId, h.status(), false, h.lines().stream()
                    .map(l -> new ReservedLine(l.sku(), "FC1", l.qty(), l.group(), List.of())).toList());
        }
    }

    /** Payments: one PaymentIntent per order key (idempotent); cancelling releases the authorisation. */
    static final class Payments implements OrderPaymentsApi {

        final Faults faults = new Faults();
        private final Map<String, String> statusById = new LinkedHashMap<>();
        private final Map<String, String> idByOrder = new LinkedHashMap<>();
        private final Map<String, Long> amountById = new LinkedHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        /** When set, the intent is created and THEN the call fails (the answer was lost): a retry or compensation must find it. */
        volatile Supplier<RuntimeException> failAfterCreate;
        /** True: every create fails after the intent exists, not just the first. */
        volatile boolean failAfterCreateAlways;

        synchronized String status(String orderId) {
            String id = idByOrder.get(orderId);
            return id == null ? null : statusById.get(id);
        }

        synchronized int intents() {
            return idByOrder.size();
        }

        synchronized long amountOf(String orderId) {
            return amountById.getOrDefault(idByOrder.get(orderId), 0L);
        }

        @Override
        public synchronized PaymentInfo create(NewPayment request, String idempotencyKey) {
            faults.enter("create");
            String id = idByOrder.get(request.orderId());
            if (id == null) {
                id = "pay-" + seq.incrementAndGet();
                idByOrder.put(request.orderId(), id);
                statusById.put(id, "REQUIRES_PAYMENT_METHOD");
                amountById.put(id, request.amountMinor());
                if (failAfterCreate != null) {
                    Supplier<RuntimeException> f = failAfterCreate;
                    failAfterCreate = failAfterCreateAlways ? f : null;
                    throw f.get();
                }
            } else if (failAfterCreate != null && failAfterCreateAlways) {
                throw failAfterCreate.get();
            }
            return info(id);
        }

        @Override
        public synchronized PaymentInfo get(String paymentId, boolean refresh) {
            faults.enter("get");
            return info(paymentId);
        }

        @Override
        public synchronized Optional<PaymentInfo> findByOrder(String orderId) {
            faults.enter("findByOrder");
            return Optional.ofNullable(idByOrder.get(orderId)).map(this::info);
        }

        @Override
        public synchronized void cancel(String paymentId, String reason, String idempotencyKey) {
            faults.enter("cancel");
            String s = statusById.get(paymentId);
            if (s == null) {
                throw new ModuleApiException(404, "payments.not-found", "No payment " + paymentId);
            }
            if ("CAPTURED".equals(s)) {
                throw new ModuleApiException(409, "payments.invalid-state", "Payment " + paymentId + " is captured");
            }
            statusById.put(paymentId, "CANCELED");
        }

        @Override
        public CaptureInfo capture(String paymentId, CaptureRequest request, String idempotencyKey) {
            faults.enter("capture");
            return new CaptureInfo("cap-1", request.amountMinor(), request.lineIds());
        }

        @Override
        public String refund(String paymentId, String idempotencyKey, List<RefundLine> lines, String reason) {
            faults.enter("refund");
            return "re-1";
        }

        @Override
        public void delivered(String orderId, Instant deliveredAt) {
            faults.enter("delivered");
        }

        @Override
        public void shipmentDelivered(String orderId, String shipmentId, Instant deliveredAt) {
            faults.enter("shipmentDelivered");
        }

        @Override
        public SplitInfo split(String orderId) {
            faults.enter("split");
            return new SplitInfo(orderId, 0, 0, 0, "USD", List.of());
        }

        @Override
        public DeliveryQuoteInfo quoteDelivery(String sellerOrgId, double distanceMiles, int packages) {
            faults.enter("quoteDelivery");
            return new DeliveryQuoteInfo(true, null, 0, 0, 1);
        }

        private PaymentInfo info(String id) {
            return new PaymentInfo(id, statusById.get(id), "pi_secret_" + id, amountById.getOrDefault(id, 0L), null, 0);
        }
    }

    /** Offers: coupon redemptions by order (one per order, idempotent); releasing gives the redemption back. */
    static final class Offers implements CheckoutOffersApi {

        final Faults faults = new Faults();
        private final Map<String, String> statusById = new LinkedHashMap<>();
        private final Map<String, String> idByOrder = new LinkedHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        final Map<String, String> inventorySkus = new LinkedHashMap<>();

        synchronized List<String> activeRedemptions() {
            return statusById.entrySet().stream().filter(e -> "REDEEMED".equals(e.getValue())).map(Map.Entry::getKey)
                    .toList();
        }

        synchronized String redemptionStatus(String orderId) {
            String id = idByOrder.get(orderId);
            return id == null ? null : statusById.get(id);
        }

        @Override
        public QuoteInfo validate(CouponRequest request) {
            faults.enter("validate");
            return new QuoteInfo(true, null, null, 100, 0, 100, "PLATFORM", List.of());
        }

        @Override
        public synchronized RedemptionInfo redeem(String idempotencyKey, CouponRequest request) {
            faults.enter("redeem");
            String id = idByOrder.get(request.orderId());
            if (id == null) {
                id = "red-" + seq.incrementAndGet();
                idByOrder.put(request.orderId(), id);
                statusById.put(id, "REDEEMED");
            }
            return new RedemptionInfo(id, "coupon-1", request.code(), statusById.get(id), 100, 0, 100, "PLATFORM", null,
                    List.of());
        }

        @Override
        public synchronized RedemptionInfo release(String redemptionId) {
            faults.enter("release");
            if (!statusById.containsKey(redemptionId)) {
                throw new ModuleApiException(404, "offers.redemption-not-found", "No redemption " + redemptionId);
            }
            statusById.put(redemptionId, "RELEASED");
            return new RedemptionInfo(redemptionId, "coupon-1", "WELCOME10", "RELEASED", 100, 0, 100, "PLATFORM", null,
                    List.of());
        }

        @Override
        public Optional<String> inventorySku(String offerId) {
            faults.enter("inventorySku");
            return Optional.ofNullable(inventorySkus.get(offerId));
        }

        @Override
        public Optional<String> shipFromLocationId(String offerId) {
            faults.enter("shipFromLocationId");
            return Optional.empty();
        }
    }

    /** Orders' own persistence steps: the order's state by id (what "no order left active" is asserted on). */
    static final class Placement implements OrderPlacementService {

        final Map<String, String> status = new ConcurrentHashMap<>();
        final Map<String, String> failure = new ConcurrentHashMap<>();
        final AtomicInteger persistCalls = new AtomicInteger();
        volatile Supplier<RuntimeException> failPersist;
        volatile Supplier<RuntimeException> failAbandon;
        final AtomicInteger abandonCalls = new AtomicInteger();

        @Override
        public void persistPending(Order order, String clientSecret) {
            persistCalls.incrementAndGet();
            if (failPersist != null) {
                throw failPersist.get();
            }
            status.putIfAbsent(order.id(), "PENDING_PAYMENT");
        }

        @Override
        public Order place(String orderId) {
            status.computeIfPresent(orderId, (k, v) -> "PENDING_PAYMENT".equals(v) ? "PLACED" : v);
            return null;
        }

        @Override
        public void abandon(String orderId, String code, String message) {
            abandonCalls.incrementAndGet();
            if (failAbandon != null) {
                throw failAbandon.get();
            }
            status.computeIfPresent(orderId, (k, v) -> "PENDING_PAYMENT".equals(v) ? "CANCELLED" : v);
            failure.put(orderId, code);
        }

        /** True when the order is stored in a state a customer or the warehouse would act on. */
        boolean active(String orderId) {
            String s = status.get(orderId);
            return "PENDING_PAYMENT".equals(s) || "PLACED".equals(s);
        }
    }
}
