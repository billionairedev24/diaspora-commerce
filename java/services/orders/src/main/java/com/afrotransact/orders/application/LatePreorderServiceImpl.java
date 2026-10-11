package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderNumberGenerator;
import com.afrotransact.orders.domain.OrderOrigin;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.orders.port.CheckoutRequestRepository.CheckoutRequest;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Late pre-order adds run synchronously (no saga): the checkout activities price, tax and split the window's lines
 * exactly like a shopper's checkout (the window may be CLOSED), then payments either confirms the intent off-session
 * on the customer's default saved card, or creates an ordinary manual-capture PaymentIntent that the customer confirms
 * on AfroTransact's pay page (the pay-now link sent by SMS, {@link PayLinkService}); never a Stripe-hosted page.
 *
 * <p>Double-charge guards: the Idempotency-Key is required and claims (customer, key) first, fixing the order id, so a
 * replay returns the same order and payments' Stripe key {@code <orderId>:create-intent} charges the card once.
 * Independently of keys, a second live late add of the same items for the same customer and window within
 * {@link PreorderSettings#lateAddDuplicateWindow()} is refused (409 orders.late-add-duplicate). Payment events are
 * routed here by {@link PaymentEventService}.
 */
@Service
class LatePreorderServiceImpl implements LatePreorderService {

    static final String WORKFLOW_PREFIX = "late-preorder-";

    private static final Logger LOG = LoggerFactory.getLogger(LatePreorderServiceImpl.class);
    private static final int MAX_KEY = 160;
    private static final int MAX_LINK_HOURS = 24;
    /** A late add waiting for the customer to authenticate the saved card is voided after this long. */
    private static final Duration AUTHENTICATION_WAIT = Duration.ofHours(48);
    /** The pay page refuses an expired link at once; the job voids the order (and cancels its intent) after this. */
    private static final Duration LINK_GRACE = Duration.ofMinutes(10);

    private final OrdersAccess access;
    private final CheckoutRequestRepository requests;
    private final OrderRepository orders;
    private final CheckoutActivities steps;
    private final ProductFactsClient catalog;
    private final PaymentsClient payments;
    private final OrderPlacementService placement;
    private final OrderNumberGenerator numbers;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final PreorderSettings preorderSettings;
    private final OrdersSettings settings;
    private final PayLinkService payLinks;
    private final Clock clock;

    LatePreorderServiceImpl(OrdersAccess access, CheckoutRequestRepository requests, OrderRepository orders,
                            CheckoutActivities steps, ProductFactsClient catalog, PaymentsClient payments,
                            OrderPlacementService placement, OrderNumberGenerator numbers,
                            DomainEventPublisher events, TransactionOperations tx, PreorderSettings preorderSettings,
                            OrdersSettings settings, PayLinkService payLinks, Clock clock) {
        this.access = access;
        this.requests = requests;
        this.orders = orders;
        this.steps = steps;
        this.catalog = catalog;
        this.payments = payments;
        this.placement = placement;
        this.numbers = numbers;
        this.events = events;
        this.tx = tx;
        this.preorderSettings = preorderSettings;
        this.settings = settings;
        this.payLinks = payLinks;
        this.clock = clock;
    }

    @Override
    public LatePreorderView create(LatePreorderCommand cmd) {
        access.onBehalfCaller();
        validate(cmd);
        String key = cmd.idempotencyKey().trim();
        String fingerprint = Fingerprint.of(cmd.customerId() + "|" + cmd.windowId() + "|" + cmd.items() + "|"
                + cmd.fulfilment() + "|" + cmd.payment() + "|" + cmd.shipTo());
        CheckoutRequest request = claim(cmd.customerId().trim(), key, fingerprint, guard(cmd));
        if (!"STARTED".equals(request.status()) || orders.findById(request.orderId()).isPresent()) {
            return view(request, true);
        }
        Order order;
        try {
            order = build(request, cmd);
        } catch (RuntimeException e) {
            requests.release(request.customerId(), request.idempotencyKey());
            throw e;
        }
        boolean link = cmd.payment() == LatePreorderCommand.Payment.PAYMENT_LINK;
        PaymentsClient.Intent intent;
        try {
            intent = payments.createIntent(new PaymentsClient.NewIntent(order.id(), order.totals().grandTotalMinor(),
                    order.currency(), Instant.now(clock), Map.of("order_number", order.number(), "customer_id",
                    order.customerId(), "channel", "AGENT_LATE"), PaymentLines.of(order), order.customerId(),
                    List.of(), !link));
        } catch (UpstreamFailure f) {
            placement.abandon(order.id(), CheckoutCodes.PAYMENT_FAILED, f.getMessage());
            if (f.retryable()) {
                throw f;
            }
            throw new CheckoutFailed(CheckoutCodes.PAYMENT_FAILED, "payments refused the payment (" + f.code()
                    + "): " + f.getMessage(), order.number(), List.of());
        }
        Instant linkExpiresAt = link ? Instant.now(clock).plus(Duration.ofHours(linkHours(cmd))) : null;
        Order pending = !link ? order : withOrigin(order,
                order.origin().withPaymentLink(payLinks.page(order.number()), linkExpiresAt));
        Order withPayment = new Order(pending.id(), pending.number(), pending.customerId(), pending.currency(),
                pending.status(), pending.totals(), pending.shipTo(), intent.paymentId(), pending.workflowId(), null,
                null, pending.createdAt(), pending.shipments(), 0, pending.origin());
        placement.persistPending(withPayment, intent.clientSecret());
        if ("AUTHORIZED".equals(intent.status())) {
            placement.place(withPayment.id());
        } else if ("FAILED".equals(intent.status()) || "CANCELED".equals(intent.status())) {
            String message = intent.failureMessage() == null ? "The saved card was declined" : intent.failureMessage();
            placement.abandon(withPayment.id(), CheckoutCodes.PAYMENT_FAILED, message);
            throw new CheckoutFailed(CheckoutCodes.PAYMENT_FAILED, message, withPayment.number(), List.of());
        } else if (link) {
            publishLink(withPayment);
        }
        return view(requests.findByOrderId(withPayment.id()).orElseThrow(), false);
    }

    // ------------------------------------------------------------------ building the order

    private Order build(CheckoutRequest request, LatePreorderCommand cmd) {
        Map<String, ProductFactsClient.Listing> listings = catalog.listings(cmd.items().stream()
                .map(LatePreorderCommand.Item::variantAtin).distinct().toList());
        List<CartClient.Item> items = new ArrayList<>();
        String currency = null;
        for (LatePreorderCommand.Item i : cmd.items()) {
            ProductFactsClient.Listing l = listings.get(i.variantAtin());
            if (l == null || l.sellerOrgId() == null) {
                throw new OrdersRuleViolation(CheckoutCodes.CART_NOT_CHECKOUTABLE, i.variantAtin()
                        + " is not a live product");
            }
            currency = currency == null ? l.currency() : currency;
            boolean retail = "PLATFORM".equalsIgnoreCase(l.ownerKind())
                    || settings.retailOrgId().equals(l.sellerOrgId());
            items.add(new CartClient.Item(i.variantAtin(), l.productAtin(), l.title(), l.imageUrl(), l.sellerOrgId(),
                    retail ? "AfroTransact Retail" : null, retail ? "PLATFORM" : "SELLER",
                    retail ? "AFROTRANSACT" : "SELLER", l.priceMinor(), l.currency(), i.quantity(), false, null));
        }
        List<CheckoutInput.Selection> selections = cmd.fulfilment() == null || cmd.fulfilment().isBlank() ? List.of()
                : List.of(new CheckoutInput.Selection(CheckoutActivitiesImpl.PREORDER_PACKAGE + cmd.windowId(),
                cmd.fulfilment(), null, null, null));
        CheckoutInput input = new CheckoutInput(request.orderId(), request.orderNumber(), request.customerId(),
                request.idempotencyKey(), request.workflowId(), currency == null ? "USD" : currency.toUpperCase(
                Locale.ROOT), cmd.shipTo(), items, selections, settings.paymentTimeout().toSeconds(),
                Instant.now(clock), null, null, Map.of(), new CheckoutInput.LateAdd(cmd.windowId()));
        CheckoutPlan plan = unwrap(() -> steps.prepare(input));
        OrderDraft draft = unwrap(() -> steps.reserve(input, plan));
        String windowCode = draft.shipments().stream().map(OrderDraft.DraftShipment::preorder)
                .filter(Objects::nonNull).map(PreorderPackage::windowCode).filter(Objects::nonNull).findFirst()
                .orElse(null);
        return OrderDrafts.toOrder(draft.withOrigin(OrderOrigin.lateAdd(cmd.actorId(), cmd.reason(), cmd.note(),
                windowCode)), null);
    }

    /** The checkout activities report refusals as Temporal failures typed with the orders code. */
    private static <T> T unwrap(Supplier<T> step) {
        try {
            return step.get();
        } catch (ApplicationFailure f) {
            if (f.getType() != null && f.getType().startsWith("orders.") && f.isNonRetryable()) {
                throw new OrdersRuleViolation(f.getType(), f.getOriginalMessage());
            }
            throw new UpstreamFailure("orders", 503, CheckoutCodes.UPSTREAM_UNAVAILABLE, f.getOriginalMessage(),
                    true, List.of(), f);
        }
    }

    private static Order withOrigin(Order o, OrderOrigin origin) {
        return new Order(o.id(), o.number(), o.customerId(), o.currency(), o.status(), o.totals(), o.shipTo(),
                o.paymentId(), o.workflowId(), o.cancelReason(), o.placedAt(), o.createdAt(), o.shipments(),
                o.version(), origin);
    }

    private void publishLink(Order order) {
        ShipTo to = order.shipTo();
        Instant expiresAt = order.origin().paymentLinkExpiresAt();
        String windowId = order.preorderShipments().stream().map(s -> s.preorder().windowId()).findFirst()
                .orElse(null);
        tx.executeWithoutResult(status -> events.publish(OrdersEvents.PREORDER_PAYMENT_LINK_CREATED,
                OrdersEvents.AGGREGATE, order.id(), new OrdersEventPayloads.PreorderPaymentLinkCreated(order.id(),
                        order.number(), order.customerId(), windowId, order.origin().attribution() == null ? null
                        : order.origin().attribution().po(), payLinks.link(order.id(), order.number(), expiresAt),
                        expiresAt, order.totals().grandTotalMinor(), order.currency(),
                        to == null ? null : to.fullName(), to == null ? null : to.phone())));
    }

    // ------------------------------------------------------------------ payment outcome + expiry

    @Override
    public boolean onPayment(String orderId, boolean authorized, boolean cancelled, String failureMessage) {
        Optional<CheckoutRequest> request = requests.findByOrderId(orderId);
        if (request.isEmpty() || !request.get().workflowId().startsWith(WORKFLOW_PREFIX)) {
            return false;
        }
        Optional<Order> order = orders.findById(orderId);
        if (order.isEmpty() || order.get().status() != OrderStatus.PENDING_PAYMENT) {
            return true;
        }
        if (authorized) {
            placement.place(orderId);
        } else if (cancelled || order.get().origin().paymentLinkUrl() == null) {
            // A pay-now link stays payable after a declined attempt (the customer tries again on the pay page, same
            // PaymentIntent); a declined saved card does not.
            placement.abandon(orderId, CheckoutCodes.PAYMENT_FAILED, failureMessage == null ? "The payment failed"
                    : failureMessage);
        }
        return true;
    }

    @Override
    public int expireUnpaid() {
        Instant now = Instant.now(clock);
        int voided = 0;
        for (String id : orders.lateAddsAwaitingPayment(preorderSettings.jobBatch())) {
            Optional<Order> found = orders.findById(id);
            if (found.isEmpty()) {
                continue;
            }
            Order o = found.get();
            Instant expiresAt = o.origin().paymentLinkExpiresAt();
            boolean expired = expiresAt != null ? now.isAfter(expiresAt.plus(LINK_GRACE))
                    : o.createdAt() != null && now.isAfter(o.createdAt().plus(AUTHENTICATION_WAIT));
            if (!expired) {
                continue;
            }
            if (o.paymentId() != null) {
                try {
                    payments.cancel(o.paymentId(), "Late pre-order add " + o.number() + " was not paid in time");
                } catch (UpstreamFailure f) {
                    if (f.retryable()) {
                        LOG.warn("Could not cancel the payment of {} ({}); retrying later", o.number(), f.code());
                        continue;
                    }
                    LOG.info("Payment of {} not cancellable ({})", o.number(), f.code());
                }
            }
            placement.abandon(o.id(), CheckoutCodes.PAYMENT_TIMEOUT, expiresAt != null
                    ? "The pay-now link expired unpaid" : "The customer did not authenticate the saved card");
            voided++;
        }
        return voided;
    }

    // ------------------------------------------------------------------ helpers

    private void validate(LatePreorderCommand cmd) {
        String key = cmd.idempotencyKey() == null ? "" : cmd.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > MAX_KEY) {
            throw new OrdersRuleViolation("orders.idempotency-key-required",
                    "An Idempotency-Key header (1-" + MAX_KEY + " characters) is required");
        }
        if (blank(cmd.customerId()) || blank(cmd.windowId()) || blank(cmd.actorId())) {
            throw new OrdersRuleViolation("orders.invalid-request", "customerId, windowId and actorId are required");
        }
        if (cmd.items().isEmpty() || cmd.items().stream().anyMatch(i -> blank(i.variantAtin()) || i.quantity() < 1)) {
            throw new OrdersRuleViolation("orders.invalid-request", "At least one item with a quantity of 1 or more");
        }
        ShipTo to = cmd.shipTo();
        if (to == null || blank(to.line1()) || blank(to.city()) || blank(to.state()) || blank(to.postalCode())) {
            throw new OrdersRuleViolation(CheckoutCodes.ADDRESS_NOT_FOUND, "A delivery address (line1, city, state, "
                    + "postal code) is required");
        }
        if (Boolean.FALSE.equals(cmd.phoneConfirmed())) {
            throw new OrdersRuleViolation("orders.phone-not-confirmed", "Confirm the customer's phone number first");
        }
        if (blank(cmd.reason())) {
            throw new OrdersRuleViolation("orders.reason-required", "Say why the order is added late");
        }
        if (cmd.payment() == null) {
            throw new OrdersRuleViolation("orders.invalid-request", "payment is SAVED_CARD or PAYMENT_LINK");
        }
        LatePreorderCommand.LateAddSettings s = cmd.lateAdd();
        if (s != null && cmd.payment() == LatePreorderCommand.Payment.SAVED_CARD
                && Boolean.FALSE.equals(s.savedCard())) {
            throw new OrdersRuleViolation("orders.late-add-payment-disabled", "Charging the saved card is switched "
                    + "off for late adds of this pre-order");
        }
        if (s != null && cmd.payment() == LatePreorderCommand.Payment.PAYMENT_LINK
                && Boolean.FALSE.equals(s.paymentLink())) {
            throw new OrdersRuleViolation("orders.late-add-payment-disabled", "Pay-now links are switched off for "
                    + "late adds of this pre-order");
        }
    }

    private int linkHours(LatePreorderCommand cmd) {
        Integer hours = cmd.lateAdd() == null ? null : cmd.lateAdd().linkExpiryHours();
        return Math.clamp(hours == null ? preorderSettings.linkExpiryHours() : hours, 1, MAX_LINK_HOURS);
    }

    /** sha-256 of the window and the item set (sorted ATIN × quantity): what makes two late adds the same order. */
    private static String guard(LatePreorderCommand cmd) {
        Map<String, Integer> items = new TreeMap<>();
        cmd.items().forEach(i -> items.merge(i.variantAtin().trim(), i.quantity(), Integer::sum));
        return Fingerprint.of(cmd.windowId().trim() + "|" + items);
    }

    /**
     * Claims (customer, key). A replay of the key returns its request. A new key first checks, under a per-customer
     * advisory lock (so two concurrent new keys cannot both pass), that no live late add of the same window and items
     * was created within the duplicate window.
     */
    private CheckoutRequest claim(String customerId, String key, String fingerprint, String guard) {
        Optional<CheckoutRequest> replay = requests.find(customerId, key);
        if (replay.isPresent()) {
            return sameRequest(replay.get(), key, fingerprint);
        }
        return tx.execute(status -> {
            requests.lockLateAdds(customerId);
            Instant since = Instant.now(clock).minus(preorderSettings.lateAddDuplicateWindow());
            Optional<CheckoutRequest> recent = requests.recentLateAdd(customerId, guard, since);
            if (recent.isPresent() && !recent.get().idempotencyKey().equals(key)) {
                throw new ConflictException(CheckoutCodes.LATE_ADD_DUPLICATE, "The same late add (order "
                        + recent.get().orderNumber() + ") was created for this customer a moment ago; check the "
                        + "window's orders before adding it again");
            }
            int year = Instant.now(clock).atZone(ZoneId.of(settings.zone())).getYear();
            for (int attempt = 0; attempt < 5; attempt++) {
                String orderId = UUID.randomUUID().toString();
                CheckoutRequest candidate = new CheckoutRequest(customerId, key, fingerprint, orderId,
                        numbers.next(year), WORKFLOW_PREFIX + orderId, "STARTED", null, null, null, null,
                        Instant.now(clock));
                CheckoutRequestRepository.Claim result = requests.claim(candidate);
                if (result.claimed()) {
                    requests.markLateAddGuard(orderId, guard);
                    return candidate;
                }
                if (result.existing() != null) {
                    return sameRequest(result.existing(), key, fingerprint);
                }
            }
            throw new IllegalStateException("Could not allocate an order number");
        });
    }

    private static CheckoutRequest sameRequest(CheckoutRequest existing, String key, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("orders.idempotency-key-reused", "Idempotency-Key " + key
                    + " was already used for a different order");
        }
        return existing;
    }

    private LatePreorderView view(CheckoutRequest r, boolean replayed) {
        if (OrderPlacementServiceImpl.STATUS_FAILED.equals(r.status())) {
            throw new CheckoutFailed(r.failureCode(), r.failureMessage(), r.orderNumber(), List.of());
        }
        Order o = orders.findById(r.orderId()).orElseThrow(() -> new ConflictException("orders.late-add-in-flight",
                "This late add is still being created; retry"));
        List<Shipment> pre = o.preorderShipments();
        Map<String, String> windows = new LinkedHashMap<>();
        pre.forEach(s -> windows.putIfAbsent(s.preorder().windowId(), s.preorder().fulfilment().name()));
        String windowId = windows.keySet().stream().findFirst().orElse(null);
        Instant linkExpiresAt = o.origin().paymentLinkExpiresAt();
        boolean link = o.origin().paymentLinkUrl() != null;
        return new LatePreorderView(o.id(), o.number(), o.status() == OrderStatus.PENDING_PAYMENT ? "AWAITING_PAYMENT"
                : o.status().name(), link ? "PAYMENT_LINK" : "SAVED_CARD",
                link && linkExpiresAt != null ? payLinks.link(o.id(), o.number(), linkExpiresAt) : null,
                linkExpiresAt, o.totals().grandTotalMinor(),
                pre.stream().mapToLong(Shipment::shippingMinor).sum(), o.currency(), windowId,
                windowId == null ? null : windows.get(windowId), o.origin().channel().name(), o.origin().tags(),
                o.shipments().stream().map(Shipment::id).toList(), replayed);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
