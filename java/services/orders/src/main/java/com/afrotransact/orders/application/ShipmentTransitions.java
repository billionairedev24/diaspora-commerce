package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.ShipmentTransitionPolicy;
import com.afrotransact.orders.domain.StatusChange;
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionDecision;
import com.afrotransact.orders.domain.TransitionResult;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.ShipmentFeed;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies shipment transitions in the caller's transaction: lock the order (transitions of one order are
 * serialised), skip already-seen event ids, run the state machine, write the shipment, the history row, the outbox
 * events and the follow-up tasks (capture, settlement, refund) together, and push the live feed after commit.
 */
@Component
class ShipmentTransitions {

    private final OrderRepository orders;
    private final FollowUpRepository followUps;
    private final ShipmentTransitionPolicy policy;
    private final DomainEventPublisher events;
    private final ShipmentFeed feed;
    private final Clock clock;

    ShipmentTransitions(OrderRepository orders, FollowUpRepository followUps, ShipmentTransitionPolicy policy,
                        DomainEventPublisher events, ShipmentFeed feed, Clock clock) {
        this.orders = orders;
        this.followUps = followUps;
        this.policy = policy;
        this.events = events;
        this.feed = feed;
        this.clock = clock;
    }

    /** One transition request. {@code actorFor} authorises the caller for the loaded shipment. */
    record Request(String shipmentId, ShipmentStatus to, BiFunction<Order, Shipment, TransitionActor> actorFor,
                   String reason, TransitionSource source, String eventId, Instant externalAt, CourierProof proof,
                   Consumer<Shipment> precheck) {
    }

    /** What happened: {@code replayed} when the event id was already applied earlier. */
    record Outcome(Order order, Shipment shipment, TransitionDecision.Kind kind, String note, boolean replayed) {

        boolean applied() {
            return kind == TransitionDecision.Kind.ALLOW && !replayed;
        }
    }

    /** Must run inside the caller's transaction (not proxied, so a refusal the caller handles does not doom it). */
    Outcome apply(Request r) {
        String orderId = orders.orderIdOfShipment(r.shipmentId()).orElseThrow(() -> new NotFoundException(
                "orders.shipment-not-found", "Shipment " + r.shipmentId() + " not found"));
        Order order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException(
                "orders.shipment-not-found", "Shipment " + r.shipmentId() + " not found"));
        Shipment shipment = order.shipment(r.shipmentId());
        TransitionActor actor = r.actorFor().apply(order, shipment);
        String eventId = r.eventId() == null || r.eventId().isBlank() ? UUID.randomUUID().toString()
                : r.eventId().trim();
        if (orders.seen(shipment.id(), eventId)) {
            return new Outcome(order, shipment, TransitionDecision.Kind.ALLOW, "already applied", true);
        }
        if (r.precheck() != null) {
            r.precheck().accept(shipment);
        }
        Step step = step(order, shipment, r.to(), actor, r.reason(), r.source(), eventId, r.externalAt(), r.proof());
        if (!step.result().applied()) {
            return new Outcome(order, shipment, step.result().kind(), step.result().note(), false);
        }
        orders.update(step.order());
        return new Outcome(step.order(), step.result().shipment(), TransitionDecision.Kind.ALLOW, null, false);
    }

    /** Result of one in-memory step on a locked order (the caller writes the order). */
    record Step(Order order, TransitionResult result) {
    }

    /**
     * Runs one transition on an already locked order and writes history, events and follow-ups. The caller persists
     * the returned order with {@link OrderRepository#update}.
     */
    Step step(Order order, Shipment shipment, ShipmentStatus to, TransitionActor actor, String reason,
              TransitionSource source, String eventId, Instant externalAt, CourierProof proof) {
        Instant now = Instant.now(clock);
        TransitionResult result = shipment.transition(to, actor, reason, source, eventId, now, externalAt, policy);
        if (!result.applied()) {
            return new Step(order, result);
        }
        Shipment next = result.shipment();
        StatusChange change = result.change();
        Order updated = order.withShipment(next);
        orders.appendHistory(change, proof == null ? null : proof.redacted());
        if (order.placedAt() != null || to == ShipmentStatus.PLACED) {
            events.publish(OrdersEvents.SHIPMENT_STATUS_CHANGED, OrdersEvents.AGGREGATE, order.id(),
                    OrdersEventPayloads.ShipmentStatusChanged.of(updated, next, change));
        }
        if (to == ShipmentStatus.DELIVERED) {
            events.publish(OrdersEvents.SHIPMENT_DELIVERED, OrdersEvents.AGGREGATE, order.id(),
                    OrdersEventPayloads.ShipmentDelivered.of(updated, next, change));
        }
        if (to == ShipmentStatus.CANCELLED && order.placedAt() != null) {
            events.publish(OrdersEvents.SHIPMENT_CANCELLED, OrdersEvents.AGGREGATE, order.id(),
                    OrdersEventPayloads.ShipmentCancelled.of(updated, next, change));
        }
        if (order.placedAt() != null && updated.status() == OrderStatus.CANCELLED
                && order.status() != OrderStatus.CANCELLED) {
            events.publish(OrdersEvents.ORDER_CANCELLED, OrdersEvents.AGGREGATE, order.id(),
                    new OrdersEventPayloads.OrderCancelled(order.id(), order.number(), order.customerId(),
                            "orders.all-shipments-cancelled", "Every shipment was cancelled", true, now));
        }
        if (order.placedAt() != null) {
            enqueueFollowUps(updated, shipment, next, now);
        }
        publishAfterCommit(new ShipmentFeed.Update(order.number(), next.id(), next.sellerOrgId(),
                change.from().wire(), change.to().wire(), change.actorKind().name(), change.source().wire(),
                change.occurredAt()));
        return new Step(updated, result);
    }

    private void enqueueFollowUps(Order order, Shipment before, Shipment after, Instant now) {
        ShipmentStatus to = after.status();
        boolean handedOver = to.handedOver() && !before.status().handedOver();
        boolean pickupReady = to == ShipmentStatus.READY && after.deliveryMethod() == DeliveryMethod.PICKUP;
        if ((handedOver || pickupReady) && !after.captured()) {
            followUps.enqueue(task("capture:" + after.id(), FollowUpRepository.Kind.CAPTURE, order, after, now));
        }
        if (to == ShipmentStatus.DELIVERED && order.shipments().stream().filter(Shipment::live)
                .allMatch(s -> s.status() == ShipmentStatus.DELIVERED || s.status() == ShipmentStatus.RETURNED)) {
            followUps.enqueue(task("settle:" + order.id(), FollowUpRepository.Kind.SETTLE, order, null, now));
        }
        if (to == ShipmentStatus.CANCELLED) {
            followUps.enqueue(task("cancel:" + after.id(), FollowUpRepository.Kind.CANCEL, order, after, now));
        }
    }

    private static FollowUpRepository.FollowUp task(String key, FollowUpRepository.Kind kind, Order order,
                                                    Shipment shipment, Instant now) {
        return new FollowUpRepository.FollowUp(UUID.randomUUID().toString(), key, kind, order.id(),
                shipment == null ? null : shipment.id(), 0, now);
    }

    private void publishAfterCommit(ShipmentFeed.Update update) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            feed.publish(update);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                feed.publish(update);
            }
        });
    }
}
