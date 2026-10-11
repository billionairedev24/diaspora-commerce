package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.LatePaymentMethod;
import com.afrotransact.deliveryruns.domain.LateAddSettings;
import com.afrotransact.deliveryruns.domain.Money;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderSummary;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.OrdersGateway;
import com.afrotransact.deliveryruns.port.OrdersGateway.Address;
import com.afrotransact.deliveryruns.port.OrdersGateway.Item;
import com.afrotransact.deliveryruns.port.OrdersGateway.LateAddTerms;
import com.afrotransact.deliveryruns.port.OrdersGateway.LatePreorderRequest;
import com.afrotransact.deliveryruns.port.OrdersGateway.LatePreorderResult;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSequences;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.ProductSearch;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class PreorderOrderServiceImpl implements PreorderOrderService {

    static final int LATE_NUMBER_START = 90;
    /** Same limit as orders-service's Idempotency-Key. */
    static final int MAX_KEY = 160;
    /** orders-service accepts payment links of at most 24 hours. */
    static final int ORDERS_MAX_LINK_HOURS = LateAddSettings.MAX_LINK_EXPIRY_HOURS;

    private final PreorderOrderRepository orders;
    private final PreorderSettingsRepository settings;
    private final PreorderSequences sequences;
    private final OrdersGateway ordersService;
    private final ProductSearch search;
    private final PreorderSupport support;
    private final PreorderEventPublisher events;
    private final PermissionChecker permissions;
    private final TransactionOperations tx;
    private final Clock clock;

    PreorderOrderServiceImpl(PreorderOrderRepository orders, PreorderSettingsRepository settings,
                             PreorderSequences sequences, OrdersGateway ordersService, ProductSearch search,
                             PreorderSupport support,
                             PreorderEventPublisher events, PermissionChecker permissions, TransactionOperations tx,
                             Clock clock) {
        this.orders = orders;
        this.settings = settings;
        this.sequences = sequences;
        this.ordersService = ordersService;
        this.search = search;
        this.support = support;
        this.events = events;
        this.permissions = permissions;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage orders(UUID windowId, OrderFilter filter, int page, int size) {
        permissions.require(DeliveryRunsPermissions.READ);
        support.find(windowId);
        int pageSize = Math.min(Math.max(size, 1), 200);
        int pageNo = Math.max(page, 0);
        OrderFilter f = filter == null ? OrderFilter.ALL : filter;
        List<PreorderOrder> matching = orders.findByWindow(windowId).stream().filter(f::matches).toList();
        return new OrderPage(matching.stream().skip((long) pageNo * pageSize).limit(pageSize).toList(),
                matching.size(), pageNo, pageSize);
    }

    @Override
    public List<ProductSearch.Product> products(UUID windowId) {
        permissions.require(DeliveryRunsPermissions.READ);
        PreorderWindow w = support.find(windowId);
        String market = w.details().targets().isEmpty() ? "US" : w.details().targets().get(0).country();
        if (w.details().categories().isEmpty()) {
            return List.of();
        }
        return search.inCategories(w.details().categories(), market, 200);
    }

    @Override
    @Transactional(readOnly = true)
    public PreorderSummary summary(UUID windowId) {
        permissions.require(DeliveryRunsPermissions.READ);
        PreorderWindow w = support.find(windowId);
        return PreorderSummary.of(w, orders.findByWindow(windowId), Instant.now(clock));
    }

    @Override
    @Transactional
    public CsvExport export(UUID windowId, ExportKind kind, OrderFilter filter) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_EXPORT);
        Instant now = Instant.now(clock);
        PreorderWindow w = support.find(windowId);
        List<PreorderOrder> all = orders.findByWindow(windowId);
        OrderFilter f = filter == null ? OrderFilter.ALL : filter;
        List<List<String>> rows = switch (kind) {
            case ORDERS -> PreorderCsv.orders(w, all);
            case ORDERS_FILTERED -> PreorderCsv.orders(w, all.stream().filter(f::matches).toList());
            case PICKING_LIST -> PreorderCsv.pickingList(PreorderSummary.productUnits(all));
            case ADVERTORIAL_REPORT -> PreorderCsv.advertorialReport(PreorderSummary.of(w, all, now));
        };
        CsvExport export = new CsvExport(w.code() + "-" + kind.path() + ".csv", rows);
        events.exportCreated(w, kind.path(), export.size(),
                kind == ExportKind.ORDERS_FILTERED ? f.describe() : Map.of(), actor.subject(), now);
        return export;
    }

    @Override
    public LateOrderResult addLateOrder(UUID windowId, LateOrderCommand c) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_LATE_ADD);
        Instant now = Instant.now(clock);
        PreorderWindow w = support.find(windowId);
        PreorderStatus status = w.status(now);
        if (status == PreorderStatus.DELIVERED) {
            throw new RuleViolation("delivery-runs.preorder-delivered", "This pre-order was already delivered");
        }
        if (status != PreorderStatus.OPEN && status != PreorderStatus.CLOSED) {
            throw new RuleViolation("delivery-runs.preorder-late-add-not-allowed",
                    "Late orders can be added to open or closed pre-orders (this one is " + status + ")");
        }
        validate(c);
        PaymentSettings payment = settings.settings().payment();
        LateAddSettings late = payment.lateAdd();
        if ((c.payment() == LatePaymentMethod.SAVED_CARD && !late.savedCard())
                || (c.payment() == LatePaymentMethod.PAYMENT_LINK && !late.paymentLink())) {
            throw new RuleViolation("delivery-runs.late-payment-not-allowed",
                    "Late orders cannot be paid by " + c.payment() + " (pre-order payment settings)");
        }
        PreorderFulfilment fulfilment = w.details().door() ? PreorderFulfilment.DOOR : PreorderFulfilment.PICKUP;
        Money fee = fulfilment == PreorderFulfilment.DOOR
                ? w.doorFee(payment).orElseThrow(() -> new RuleViolation("delivery-runs.preorder-fee-missing",
                        "No door delivery fee is set for " + w.details().currency()))
                : w.pickupFee(payment);
        // Never invented here: a key generated per request would make every retry a new order and a new charge.
        String key = c.idempotencyKey() == null ? "" : c.idempotencyKey().trim();
        Address address = address(w, c);
        int linkHours = Math.min(late.linkExpiryHours(), ORDERS_MAX_LINK_HOURS);
        LatePreorderResult r = ordersService.placeLatePreorder(new LatePreorderRequest(key, w.id(),
                c.customerId().trim(), address, true, c.items(), fulfilment.name(), c.payment(), c.reason().label(),
                c.note(), actor.subject(), new LateAddTerms(late.savedCard(), late.paymentLink(), linkHours)));
        String note = c.reason().label() + (c.payment() == LatePaymentMethod.PAYMENT_LINK
                ? " · pay-now link sent by SMS (expires in " + linkHours + " h)" : " · saved card charged")
                + " · by " + actor.subject() + (c.note() == null || c.note().isBlank() ? "" : " · " + c.note().trim());
        PreorderOrder saved = tx.execute(s -> {
            boolean replay = orders.findById(r.orderId()).filter(o -> o.status() == PreorderOrderStatus.LATE_ADD)
                    .isPresent();
            PreorderOrder o = record(w, c, r, address, fulfilment, fee, note, now);
            if (!replay) {
                // Once per order: a replayed key (staff retry after a timeout) must not text the link twice.
                Instant expires = c.payment() != LatePaymentMethod.PAYMENT_LINK ? null
                        : r.paymentLinkExpiresAt() != null ? r.paymentLinkExpiresAt()
                        : now.plus(Duration.ofHours(linkHours));
                events.lateOrderAdded(w, o, c.payment().name(), r.paymentLinkUrl(), expires, c.reason().label(),
                        actor.subject());
            }
            return o;
        });
        return new LateOrderResult(saved, r.status(), r.paymentLinkUrl());
    }

    private PreorderOrder record(PreorderWindow w, LateOrderCommand c, LatePreorderResult r, Address address,
                                 PreorderFulfilment fulfilment, Money fee, String note, Instant now) {
        Optional<PreorderOrder> existing = orders.findById(r.orderId());
        String number = existing.filter(o -> o.status() == PreorderOrderStatus.LATE_ADD).map(PreorderOrder::number)
                .orElseGet(() -> w.code() + "-L" + sequences.next("late:" + w.id(), LATE_NUMBER_START));
        long total = r.totalMinor() > 0 ? r.totalMinor() : fee.minor();
        List<PreorderLine> lines = !r.lines().isEmpty() ? r.lines()
                : c.items().stream().map(i -> new PreorderLine(i.atin(), i.atin(), null, i.qty())).toList();
        PreorderPackage pkg = new PreorderPackage(r.shipmentId(), null, total, fee.minor(), lines, false);
        PreorderOrder late = new PreorderOrder(r.orderId(), number, w.id(), c.customerId().trim(),
                pick(r.customerName(), address.fullName()), pick(r.phone(), address.phone()), r.email(),
                new PreorderOrder.Address(pick(r.line1(), address.line1()), pick(r.city(), address.city()),
                        pick(r.region(), address.state()), pick(r.zip(), address.postalCode())), c.note(),
                now, AttributionChannel.AGENT_LATE, AttributionChannel.trackingTag("agent", null, w.code()),
                List.of(pkg), fulfilment, fee.currency(), PreorderOrderStatus.LATE_ADD, truncate(note, 500), now, now,
                0);
        if (existing.isEmpty()) {
            return orders.insert(late);
        }
        PreorderOrder prev = existing.get();
        PreorderOrder merged = new PreorderOrder(prev.orderId(), number, w.id(), late.customerId(),
                pick(prev.customerName(), late.customerName()), pick(prev.phone(), late.phone()),
                pick(prev.email(), late.email()), prev.address().line() != null ? prev.address() : late.address(),
                pick(prev.deliveryNote(), late.deliveryNote()), prev.placedAt(), AttributionChannel.AGENT_LATE,
                late.trackingTag(), prev.packages().isEmpty() ? late.packages() : prev.packages(), prev.fulfilment(),
                prev.currency(), PreorderOrderStatus.LATE_ADD, late.note(), prev.createdAt(), now, prev.version());
        return orders.update(merged);
    }

    /**
     * The address snapshot orders needs: the one sent, else the new address (street, zip) in the window's first city;
     * a saved address id must come with its snapshot.
     */
    private static Address address(PreorderWindow w, LateOrderCommand c) {
        AreaKey first = w.details().targets().stream().filter(AreaKey::isCity).findFirst()
                .orElse(w.details().targets().isEmpty() ? null : w.details().targets().get(0));
        Address a = c.address();
        boolean snap = a != null;
        String line1 = snap && !isBlank(a.line1()) ? a.line1() : c.newAddress() != null ? c.newAddress().line1() : null;
        String zip = snap && !isBlank(a.postalCode()) ? a.postalCode()
                : c.newAddress() != null ? c.newAddress().zip() : null;
        String town = snap && !isBlank(a.city()) ? a.city() : first != null ? first.city() : null;
        String state = snap && !isBlank(a.state()) ? a.state() : first != null ? first.region() : null;
        if (isBlank(line1) || isBlank(zip) || isBlank(town) || isBlank(state)) {
            throw new RuleViolation("delivery-runs.address-details-required",
                    "Send the delivery address (street, city, state, postal code) with the saved address id");
        }
        return new Address(blankToNull(c.addressId()), snap ? a.fullName() : null, snap ? a.phone() : null, line1,
                snap ? a.line2() : null, town, state, zip,
                snap && !isBlank(a.country()) ? a.country() : first != null ? first.country() : "US",
                snap ? a.instructions() : null);
    }

    private static void validate(LateOrderCommand c) {
        String key = c.idempotencyKey() == null ? "" : c.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > MAX_KEY) {
            throw new RuleViolation("delivery-runs.idempotency-key-required", "Send an Idempotency-Key header (1-"
                    + MAX_KEY + " characters), the same one on every retry of this late order");
        }
        if (c.customerId() == null || c.customerId().isBlank()) {
            throw new RuleViolation("delivery-runs.customer-required", "Choose the customer");
        }
        if (!Boolean.TRUE.equals(c.phoneConfirmed())) {
            throw new RuleViolation("delivery-runs.phone-not-confirmed",
                    "Confirm the phone number with the customer first");
        }
        boolean saved = c.addressId() != null && !c.addressId().isBlank();
        boolean fresh = c.newAddress() != null;
        if (saved == fresh) {
            throw new RuleViolation("delivery-runs.address-required", "Send either a saved address or a new address");
        }
        if (fresh && (isBlank(c.newAddress().line1()) || isBlank(c.newAddress().zip())
                || isBlank(c.newAddress().placeId()))) {
            throw new RuleViolation("delivery-runs.address-not-verified",
                    "A new address needs a street, a postal code and its verified place id");
        }
        if (c.items() == null || c.items().isEmpty() || c.items().size() > 50) {
            throw new RuleViolation("delivery-runs.items-required", "Add between 1 and 50 products");
        }
        for (Item i : c.items()) {
            if (i == null || isBlank(i.atin()) || i.qty() < 1 || i.qty() > 99) {
                throw new RuleViolation("delivery-runs.invalid-items", "Every item needs an ATIN and a quantity 1–99");
            }
        }
        if (c.payment() == null) {
            throw new RuleViolation("delivery-runs.payment-required", "Choose saved card or pay-now link");
        }
        if (c.reason() == null) {
            throw new RuleViolation("delivery-runs.reason-required", "Choose why the order is late");
        }
        if (c.note() != null && c.note().length() > 500) {
            throw new RuleViolation("delivery-runs.invalid-note", "The note is at most 500 characters");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String pick(String preferred, String other) {
        return isBlank(preferred) ? other : preferred;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
