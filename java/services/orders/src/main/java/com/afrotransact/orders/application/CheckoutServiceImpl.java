package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderNumberGenerator;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.orders.port.CheckoutRequestRepository.CheckoutRequest;
import com.afrotransact.orders.port.HandoverCodeVault;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

/**
 * Idempotency: (customer, Idempotency-Key) is claimed first and fixes the order id, order number and workflow id, so
 * a retry — even a concurrent one — attaches to the same saga. The claim is released when the saga could not be
 * started (cart or address unreachable), so the shopper can retry with the same key.
 */
@Service
class CheckoutServiceImpl implements CheckoutService {

    private static final int MAX_KEY = 160;
    private static final Set<String> AUTHORIZED = Set.of("AUTHORIZED", "PARTIALLY_CAPTURED", "CAPTURED");
    private static final Set<String> FAILED = Set.of("FAILED", "CANCELED");

    private final OrdersAccess access;
    private final CheckoutRequestRepository requests;
    private final OrderRepository orders;
    private final CartClient cart;
    private final AddressBook addresses;
    private final CheckoutWorkflows workflows;
    private final PaymentsClient payments;
    private final OrderNumberGenerator numbers;
    private final HandoverCodeVault vault;
    private final OrdersSettings settings;
    private final Clock clock;

    CheckoutServiceImpl(OrdersAccess access, CheckoutRequestRepository requests, OrderRepository orders,
                        CartClient cart, AddressBook addresses, CheckoutWorkflows workflows, PaymentsClient payments,
                        OrderNumberGenerator numbers, HandoverCodeVault vault, OrdersSettings settings, Clock clock) {
        this.access = access;
        this.requests = requests;
        this.orders = orders;
        this.cart = cart;
        this.addresses = addresses;
        this.workflows = workflows;
        this.payments = payments;
        this.numbers = numbers;
        this.vault = vault;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public CheckoutView checkout(CheckoutCommand cmd) {
        Actor customer = access.customer();
        String key = cmd.idempotencyKey() == null ? "" : cmd.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > MAX_KEY) {
            throw new OrdersRuleViolation("orders.idempotency-key-required",
                    "An Idempotency-Key header (1-" + MAX_KEY + " characters) is required");
        }
        if (cmd.addressId() == null || cmd.addressId().isBlank()) {
            throw new OrdersRuleViolation("orders.address-required", "Choose a delivery address");
        }
        String fingerprint = Fingerprint.of(cmd.addressId().trim() + "|" + cmd.selections() + "|" + cmd.lines()
                + (cmd.couponCode() == null ? "" : "|" + cmd.couponCode()));
        Claimed claimed = claim(customer.subject(), key, fingerprint);
        CheckoutRequest request = claimed.request();
        boolean start = claimed.fresh()
                || "STARTED".equals(request.status()) && !workflows.exists(request.workflowId());
        if (start) {
            try {
                workflows.start(input(request, cmd));
            } catch (RuntimeException e) {
                requests.release(customer.subject(), key);
                throw e;
            }
        }
        return await(request.orderId());
    }

    @Override
    public CheckoutView confirmPayment(String orderNumber) {
        CheckoutRequest request = ownRequest(orderNumber);
        if (OrderPlacementServiceImpl.STATUS_AWAITING.equals(request.status()) && request.paymentId() != null) {
            PaymentsClient.Intent intent = payments.get(request.paymentId(), true);
            if (AUTHORIZED.contains(intent.status())) {
                workflows.signalPayment(request.workflowId(), true, null);
            } else if (FAILED.contains(intent.status())) {
                workflows.signalPayment(request.workflowId(), false, intent.failureMessage());
            }
            return awaitChange(request);
        }
        return view(request);
    }

    @Override
    public CheckoutView status(String orderNumber) {
        return view(ownRequest(orderNumber));
    }

    // ------------------------------------------------------------------ claim + input

    private record Claimed(CheckoutRequest request, boolean fresh) {
    }

    private Claimed claim(String customerId, String key, String fingerprint) {
        int year = Instant.now(clock).atZone(ZoneId.of(settings.zone())).getYear();
        for (int attempt = 0; attempt < 5; attempt++) {
            String orderId = UUID.randomUUID().toString();
            CheckoutRequest candidate = new CheckoutRequest(customerId, key, fingerprint, orderId,
                    numbers.next(year), "checkout-" + orderId, "STARTED", null, null, null, null, Instant.now(clock));
            CheckoutRequestRepository.Claim result = requests.claim(candidate);
            if (result.claimed()) {
                return new Claimed(candidate, true);
            }
            if (result.existing() != null) {
                if (!result.existing().fingerprint().equals(fingerprint)) {
                    throw new ConflictException("orders.idempotency-key-reused", "Idempotency-Key " + key
                            + " was already used for a different checkout");
                }
                return new Claimed(result.existing(), false);
            }
        }
        throw new IllegalStateException("Could not allocate an order number");
    }

    private CheckoutInput input(CheckoutRequest request, CheckoutCommand cmd) {
        CartClient.Snapshot snapshot = cart.cart(cmd.customerBearer());
        ShipTo shipTo = addresses.find(cmd.customerBearer(), cmd.addressId().trim())
                .orElseThrow(() -> new OrdersRuleViolation(CheckoutCodes.ADDRESS_NOT_FOUND,
                        "Address " + cmd.addressId() + " is not in your address book"));
        String currency = snapshot.currency() == null || snapshot.currency().isBlank() ? "USD" : snapshot.currency();
        return new CheckoutInput(request.orderId(), request.orderNumber(), request.customerId(),
                request.idempotencyKey(), request.workflowId(), currency, shipTo, select(snapshot, cmd.lines()),
                cmd.selections(), settings.paymentTimeout().toSeconds(), Instant.now(clock), cmd.couponCode());
    }

    /** Every available line, or the requested subset (quantities never above what is in the cart). */
    private static List<CartClient.Item> select(CartClient.Snapshot snapshot, List<CheckoutCommand.SelectedLine> lines) {
        if (lines.isEmpty()) {
            return snapshot.items();
        }
        Map<String, CartClient.Item> byAtin = new LinkedHashMap<>();
        snapshot.items().forEach(i -> byAtin.put(i.variantAtin(), i));
        List<CartClient.Item> out = new ArrayList<>();
        for (CheckoutCommand.SelectedLine l : lines) {
            CartClient.Item item = byAtin.get(l.variantAtin());
            if (item == null) {
                throw new OrdersRuleViolation("orders.line-not-in-cart", l.variantAtin() + " is not in your cart");
            }
            int qty = l.quantity() <= 0 ? item.quantity() : l.quantity();
            if (qty > item.quantity()) {
                throw new OrdersRuleViolation("orders.cart-changed", "Only " + item.quantity() + " of "
                        + l.variantAtin() + " are in your cart");
            }
            out.add(new CartClient.Item(item.variantAtin(), item.productAtin(), item.title(), item.imageUrl(),
                    item.sellerOrgId(), item.sellerName(), item.sellerKind(), item.fulfilment(), item.priceMinor(),
                    item.currency(), qty, item.unavailable(), item.offerId()));
        }
        return out;
    }

    // ------------------------------------------------------------------ outcome

    private CheckoutRequest ownRequest(String orderNumber) {
        Actor customer = access.customer();
        return requests.findByNumber(orderNumber)
                .filter(r -> OrdersAccess.superAdmin(customer) || r.customerId().equals(customer.subject()))
                .orElseThrow(() -> new NotFoundException("orders.order-not-found", "Order " + orderNumber
                        + " not found"));
    }

    /** Waits until the saga reached awaiting-payment, placed or failed (or the response timeout passed). */
    private CheckoutView await(String orderId) {
        long deadline = System.nanoTime() + settings.checkoutResponseTimeout().toNanos();
        CheckoutRequest current = requests.findByOrderId(orderId).orElseThrow();
        while (!settled(current) && System.nanoTime() < deadline) {
            pause();
            current = requests.findByOrderId(orderId).orElseThrow();
        }
        return view(current);
    }

    /** After a payment signal: waits for the saga to move past awaiting-payment. */
    private CheckoutView awaitChange(CheckoutRequest request) {
        long deadline = System.nanoTime() + settings.checkoutResponseTimeout().toNanos();
        CheckoutRequest current = request;
        while (OrderPlacementServiceImpl.STATUS_AWAITING.equals(current.status()) && System.nanoTime() < deadline) {
            pause();
            current = requests.findByOrderId(request.orderId()).orElseThrow();
        }
        return view(current);
    }

    private static boolean settled(CheckoutRequest r) {
        return !"STARTED".equals(r.status());
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for checkout", e);
        }
    }

    private CheckoutView view(CheckoutRequest r) {
        if (OrderPlacementServiceImpl.STATUS_FAILED.equals(r.status())) {
            throw new CheckoutFailed(r.failureCode(), r.failureMessage(), r.orderNumber(), List.of());
        }
        Optional<Order> order = orders.findById(r.orderId());
        String status = switch (r.status()) {
            case OrderPlacementServiceImpl.STATUS_PLACED -> "placed";
            case OrderPlacementServiceImpl.STATUS_AWAITING -> "awaiting_payment";
            default -> "processing";
        };
        if (order.isEmpty()) {
            return new CheckoutView(r.orderId(), r.orderNumber(), status, r.clientSecret(), r.clientSecret(), null,
                    null, List.of());
        }
        Order o = order.get();
        int count = o.shipments().size();
        return new CheckoutView(o.id(), o.number(), status, r.clientSecret(), r.clientSecret(),
                o.totals().grandTotalMinor(), o.currency(), o.shipments().stream().map(s -> shipment(s, count))
                .toList());
    }

    private CheckoutView.Shipment shipment(Shipment s, int count) {
        String code = s.handoverRequired() && s.handoverCodeSealed() != null
                ? vault.reveal(s.id(), s.handoverCodeSealed()) : null;
        String label = "Shipment " + s.seq() + " of " + count + (s.sellerName() == null ? "" : " · " + s.sellerName());
        return new CheckoutView.Shipment(s.id(), label, s.promisedBy() == null ? null : s.promisedBy().toString(),
                s.promisedBy(), s.deliveryMethod().name(), code);
    }
}
