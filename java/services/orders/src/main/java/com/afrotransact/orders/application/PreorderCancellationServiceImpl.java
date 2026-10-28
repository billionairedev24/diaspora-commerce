package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionRefused;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class PreorderCancellationServiceImpl implements PreorderCancellationService {

    private static final Logger LOG = LoggerFactory.getLogger(PreorderCancellationServiceImpl.class);

    private static final Result CANCELLED = new Result(1, 0, 0, 0);
    private static final Result ALREADY = new Result(0, 1, 0, 0);
    private static final Result SKIPPED = new Result(0, 0, 1, 0);
    private static final Result FAILED = new Result(0, 0, 0, 1);

    private final OrderRepository orders;
    private final ShipmentTransitions transitions;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    PreorderCancellationServiceImpl(OrderRepository orders, ShipmentTransitions transitions,
                                    DomainEventPublisher events, TransactionOperations tx, Clock clock) {
        this.orders = orders;
        this.transitions = transitions;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Result cancelWindow(String windowId, String reason) {
        Result total = new Result(0, 0, 0, 0);
        for (String shipmentId : orders.preorderShipmentsOfWindow(windowId)) {
            total = total.plus(cancelShipment(shipmentId, reason));
        }
        LOG.info("Pre-order window {} cancelled: {}", windowId, total);
        return total;
    }

    @Override
    public Result cancelOrders(Collection<String> orderIds, String reason) {
        Result total = new Result(0, 0, 0, 0);
        for (String orderId : new LinkedHashSet<>(orderIds)) {
            Optional<Order> order = orders.findById(orderId);
            if (order.isEmpty()) {
                LOG.warn("Cancelled run names unknown order {}; skipped", orderId);
                continue;
            }
            for (Shipment s : order.get().shipments()) {
                if (s.preorderShipment()) {
                    total = total.plus(cancelShipment(s.id(), reason));
                }
            }
        }
        LOG.info("Run cancelled, {} orders: {}", orderIds.size(), total);
        return total;
    }

    /** One shipment in its own transaction, so one stuck order never blocks the refunds of the others. */
    private Result cancelShipment(String shipmentId, String reason) {
        return tx.execute(status -> {
            Optional<Order> locked = orders.orderIdOfShipment(shipmentId).flatMap(orders::lockById);
            if (locked.isEmpty()) {
                return SKIPPED;
            }
            Order order = locked.get();
            Shipment s = order.shipment(shipmentId);
            if (!s.preorderShipment()) {
                return SKIPPED;
            }
            if (s.status() == ShipmentStatus.CANCELLED) {
                return ALREADY;
            }
            if (order.placedAt() == null || s.status() == ShipmentStatus.PENDING_PAYMENT) {
                return SKIPPED;
            }
            Instant now = Instant.now(clock);
            try {
                ShipmentTransitions.Step step = transitions.step(order, s, ShipmentStatus.CANCELLED,
                        new TransitionActor(ActorKind.PLATFORM_ADMIN, ACTOR, null), reason, TransitionSource.SYSTEM,
                        "preorder-cancelled:" + shipmentId, null, null);
                if (!step.result().applied()) {
                    return failed(order, s, "orders.cannot-cancel", step.result().note(), now);
                }
                orders.update(step.order());
                return CANCELLED;
            } catch (TransitionRefused refused) {
                return failed(order, s, refused.code(), refused.getMessage(), now);
            }
        });
    }

    private Result failed(Order order, Shipment s, String code, String message, Instant now) {
        LOG.warn("Pre-order shipment {} of {} cannot be cancelled ({}): {}", s.id(), order.number(), code, message);
        events.publish(OrdersEvents.PREORDER_REFUND_FAILED, OrdersEvents.AGGREGATE, order.id(),
                OrdersEventPayloads.PreorderRefundFailed.of(order, s, code, message, now));
        return FAILED;
    }
}
