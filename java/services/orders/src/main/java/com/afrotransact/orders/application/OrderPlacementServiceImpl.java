package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderPlacementServiceImpl implements OrderPlacementService {

    static final String STATUS_AWAITING = "AWAITING_PAYMENT";
    static final String STATUS_PLACED = "PLACED";
    static final String STATUS_FAILED = "FAILED";

    private final OrderRepository orders;
    private final CheckoutRequestRepository requests;
    private final ShipmentTransitions transitions;
    private final DomainEventPublisher events;
    private final Clock clock;

    OrderPlacementServiceImpl(OrderRepository orders, CheckoutRequestRepository requests,
                              ShipmentTransitions transitions, DomainEventPublisher events, Clock clock) {
        this.orders = orders;
        this.requests = requests;
        this.transitions = transitions;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void persistPending(Order order, String clientSecret) {
        if (orders.findById(order.id()).isEmpty()) {
            orders.insert(order);
        }
        requests.markAwaitingPayment(order.id(), order.paymentId(), clientSecret);
    }

    @Override
    @Transactional
    public Order place(String orderId) {
        Order order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("orders.order-not-found",
                "Order " + orderId + " not found"));
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return order;
        }
        Order current = order;
        for (Shipment s : order.shipments()) {
            current = transitions.step(current, current.shipment(s.id()), ShipmentStatus.PLACED,
                    TransitionActor.system(), "Payment authorised", TransitionSource.SYSTEM, "placed:" + s.id(), null,
                    null).order();
        }
        Instant now = Instant.now(clock);
        Order placed = current.withHeader(OrderStatus.PLACED, null, now);
        orders.update(placed);
        events.publish(OrdersEvents.ORDER_PLACED, OrdersEvents.AGGREGATE, placed.id(),
                OrdersEventPayloads.OrderPlaced.of(placed));
        requests.markOutcome(orderId, STATUS_PLACED, null, null);
        return placed;
    }

    @Override
    @Transactional
    public void abandon(String orderId, String code, String message) {
        orders.lockById(orderId).ifPresent(order -> {
            if (order.status() != OrderStatus.PENDING_PAYMENT) {
                return;
            }
            Order current = order;
            for (Shipment s : order.shipments()) {
                current = transitions.step(current, current.shipment(s.id()), ShipmentStatus.CANCELLED,
                        TransitionActor.system(), message == null ? code : message, TransitionSource.SYSTEM,
                        "voided:" + s.id(), null, null).order();
            }
            Instant now = Instant.now(clock);
            orders.update(current.withHeader(OrderStatus.CANCELLED, code + ": " + message, null));
            events.publish(OrdersEvents.ORDER_CANCELLED, OrdersEvents.AGGREGATE, orderId,
                    new OrdersEventPayloads.OrderCancelled(orderId, order.number(), order.customerId(), code, message,
                            false, now));
        });
        requests.markOutcome(orderId, STATUS_FAILED, code, message);
    }
}
