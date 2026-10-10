package com.afrotransact.orders.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.Page;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderQueryServiceImpl implements OrderQueryService {

    private static final int MAX_LIMIT = 100;

    private final OrdersAccess access;
    private final OrderRepository orders;
    private final OrderViews views;
    private final PaymentsClient payments;

    OrderQueryServiceImpl(OrdersAccess access, OrderRepository orders, OrderViews views, PaymentsClient payments) {
        this.access = access;
        this.orders = orders;
        this.views = views;
        this.payments = payments;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage<OrderView> mine(String cursor, int limit) {
        Actor customer = access.customer();
        Page<Order> page = orders.byCustomer(customer.subject(), offset(cursor), clamp(limit));
        Viewer viewer = new Viewer(Viewer.Kind.CUSTOMER, null);
        return page(page, page.items().stream().map(o -> views.of(o, viewer, orders.history(o.id()))).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView get(String numberOrId) {
        Order order = find(numberOrId);
        Viewer viewer = access.viewerOf(order);
        return views.of(order, viewer, orders.history(order.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage<SellerShipmentView> sellerQueue(String sellerOrgId, String status, String cursor, int limit) {
        String org = access.sellerOrg(sellerOrgId);
        ShipmentStatus filter = status == null || status.isBlank() ? null : shipmentStatus(status);
        Page<OrderRepository.SellerShipment> page = orders.sellerShipments(org, filter, offset(cursor),
                clamp(limit));
        Viewer viewer = new Viewer(Viewer.Kind.SELLER, org);
        return page(page, page.items().stream().map(r -> new SellerShipmentView(r.orderNumber(),
                r.orderStatus().name().toLowerCase(Locale.ROOT), r.placedAt(), r.shipTo(),
                views.shipment(r.shipment(), r.shipmentCount(), viewer, List.of()))).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage<OrderView> search(OrderSearchQuery q, String cursor, int limit) {
        access.staffReader();
        OrderRepository.Search criteria = new OrderRepository.Search(blank(q.number()), blank(q.customerId()),
                blank(q.sellerOrgId()), q.status() == null || q.status().isBlank() ? null : orderStatus(q.status()),
                q.shipmentStatus() == null || q.shipmentStatus().isBlank() ? null : shipmentStatus(q.shipmentStatus()),
                q.from(), q.to());
        Page<Order> page = orders.search(criteria, offset(cursor), clamp(limit));
        Viewer staff = new Viewer(Viewer.Kind.STAFF, null);
        return page(page, page.items().stream().map(o -> views.of(o, staff, List.of())).toList());
    }

    @Override
    public Optional<String> liveFeedScope() {
        Actor actor = access.current();
        if (OrdersAccess.staff(actor) || OrdersAccess.superAdmin(actor)) {
            access.staffReader();
            return Optional.empty();
        }
        return Optional.of(access.sellerOrg(null));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentsClient.Split paymentSplit(String numberOrId) {
        Order order = find(numberOrId);
        Viewer viewer = access.viewerOf(order);
        if (viewer.kind() == Viewer.Kind.CUSTOMER) {
            throw new ForbiddenActionException("orders.split-not-for-customers",
                    "The payment split is a staff and seller view");
        }
        PaymentsClient.Split split = payments.split(order.id());
        if (viewer.kind() == Viewer.Kind.STAFF) {
            return split;
        }
        return new PaymentsClient.Split(split.orderId(), split.chargeMinor(), 0, 0, split.currency(),
                split.recipients().stream().filter(r -> viewer.orgId().equals(r.recipient())).toList());
    }

    private Order find(String numberOrId) {
        Optional<Order> byNumber = orders.findByNumber(numberOrId.trim().toUpperCase(Locale.ROOT));
        if (byNumber.isPresent()) {
            return byNumber.get();
        }
        if (isUuid(numberOrId)) {
            Optional<Order> byId = orders.findById(numberOrId.trim());
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        throw OrdersAccess.notFound(numberOrId);
    }

    private static <T> OrderPage<T> page(Page<?> page, List<T> items) {
        String next = page.hasMore() ? String.valueOf(page.offset() + page.items().size()) : null;
        return new OrderPage<>(items, next, page.total());
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            throw new OrdersRuleViolation("orders.invalid-cursor", "Invalid cursor");
        }
    }

    private static int clamp(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, MAX_LIMIT);
    }

    private static ShipmentStatus shipmentStatus(String s) {
        try {
            return ShipmentStatus.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrdersRuleViolation("orders.unknown-status", "Unknown shipment status " + s);
        }
    }

    private static OrderStatus orderStatus(String s) {
        try {
            return OrderStatus.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrdersRuleViolation("orders.unknown-status", "Unknown order status " + s);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
