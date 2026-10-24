package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.OrderCouponRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Money and stock follow-ups of shipment transitions (architecture §5 "capture per shipment on carrier acceptance",
 * §17b "one charge, many transfers"):
 * <ul>
 *   <li>CAPTURE — shipment picked up (or ready at a pickup point): capture items + shipping + tax of its lines,
 *       {@code finalCapture} when no other live shipment is left uncaptured; commit the order's inventory reservation
 *       on the first AfroTransact-fulfilled pickup.</li>
 *   <li>SETTLE — every live shipment delivered: payments starts the settlement hold for the order.</li>
 *   <li>CANCEL — captured: line refund. Not captured: when nothing is left, cancel the PaymentIntent; release the
 *       reservation when no AfroTransact-fulfilled shipment remains; release the order's coupon redemption
 *       (offers-service) once every shipment is cancelled. A cancelled PREORDER shipment (window or run cancelled, or
 *       the customer's own cancellation) then publishes {@code orders.preorder.refund_settled} (REFUNDED, or VOIDED
 *       when nothing was captured) or, when the task fails for good, {@code orders.preorder.refund_failed}, so staff
 *       see each order's refund progress; stock held for its lines is released where inventory has any.</li>
 * </ul>
 * Tasks are claimed with a lease and retried with exponential backoff; upstream calls are idempotent.
 */
@Service
class FollowUpServiceImpl implements FollowUpService {

    private static final Logger LOG = LoggerFactory.getLogger(FollowUpServiceImpl.class);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final FollowUpRepository tasks;
    private final OrderRepository orders;
    private final PaymentsClient payments;
    private final InventoryClient inventory;
    private final CouponsClient coupons;
    private final OrderCouponRepository orderCoupons;
    private final TransactionOperations tx;
    private final OrdersSettings settings;
    private final DomainEventPublisher events;
    private final Clock clock;

    FollowUpServiceImpl(FollowUpRepository tasks, OrderRepository orders, PaymentsClient payments,
                        InventoryClient inventory, CouponsClient coupons, OrderCouponRepository orderCoupons,
                        TransactionOperations tx, OrdersSettings settings, DomainEventPublisher events,
                        Clock clock) {
        this.tasks = tasks;
        this.orders = orders;
        this.payments = payments;
        this.inventory = inventory;
        this.coupons = coupons;
        this.orderCoupons = orderCoupons;
        this.tx = tx;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public int runDue() {
        List<FollowUpRepository.FollowUp> due = tasks.claimDue(Instant.now(clock), LEASE, settings.followUpBatch());
        int done = 0;
        for (FollowUpRepository.FollowUp task : due) {
            try {
                run(task);
                tasks.done(task.id(), Instant.now(clock));
                done++;
            } catch (RuntimeException e) {
                int attempts = task.attempts() + 1;
                boolean permanent = e instanceof UpstreamFailure f && !f.retryable();
                if (permanent || attempts >= settings.followUpMaxAttempts()) {
                    LOG.error("Follow-up {} failed permanently after {} attempts", task.key(), attempts, e);
                    tasks.fail(task.id(), attempts, message(e));
                    reportRefundFailure(task, e);
                } else {
                    Duration backoff = Duration.ofSeconds(Math.min(1800, 5L << Math.min(attempts, 9)));
                    LOG.warn("Follow-up {} failed (attempt {}), retrying in {}: {}", task.key(), attempts, backoff,
                            message(e));
                    tasks.retry(task.id(), attempts, Instant.now(clock).plus(backoff), message(e));
                }
            }
        }
        return done;
    }

    private void run(FollowUpRepository.FollowUp task) {
        Order order = orders.findById(task.orderId()).orElseThrow(() -> new IllegalStateException(
                "Order " + task.orderId() + " of follow-up " + task.key() + " is missing"));
        switch (task.kind()) {
            case CAPTURE -> capture(order, order.shipment(task.shipmentId()));
            case SETTLE -> settle(order);
            case CANCEL -> cancelled(order, order.shipment(task.shipmentId()));
            case SETTLE_SHIPMENT -> settleShipment(order, order.shipment(task.shipmentId()));
            default -> throw new IllegalStateException("Unknown follow-up " + task.kind());
        }
    }

    private void capture(Order order, Shipment s) {
        if (!s.live()) {
            return;
        }
        if (!s.captured()) {
            boolean last = order.shipments().stream().filter(o -> !o.id().equals(s.id()))
                    .allMatch(o -> o.captured() || !o.live());
            PaymentsClient.Capture capture = payments.capture(requirePayment(order),
                    new PaymentsClient.CaptureRequest(s.id(), PaymentLines.lineIds(s), s.totalMinor(), last));
            tx.executeWithoutResult(status -> {
                Order locked = orders.lockById(order.id()).orElseThrow();
                Instant now = Instant.now(clock);
                Order next = locked;
                for (Shipment other : coveredBy(locked, s.id(), capture)) {
                    next = next.withShipment(other.withCapture(capture.captureId(), now));
                }
                if (next != locked) {
                    orders.update(next);
                }
            });
        }
        if (s.fulfilmentType().inventoryTracked()) {
            try {
                inventory.commitGroups(order.id(), s.id(), PaymentLines.lineIds(s));
            } catch (UpstreamFailure f) {
                if (f.status() != 404) {
                    throw f;
                }
                // reserved before per-line groups existed: ship the whole reservation on the first pickup
                inventory.commit(order.id());
            }
        }
    }

    /**
     * The shipments a capture paid for, not yet marked captured: the one captured and, when the payment has no Stripe
     * multicapture (payments captured every line not captured yet with this first shipment), every other live shipment
     * whose lines are all in the capture. Those are later refunded, not released, if cancelled.
     */
    static List<Shipment> coveredBy(Order order, String shipmentId, PaymentsClient.Capture capture) {
        Set<String> captured = new HashSet<>(capture.lineIds());
        List<Shipment> out = new ArrayList<>();
        for (Shipment o : order.shipments()) {
            if (o.captured()) {
                continue;
            }
            boolean self = o.id().equals(shipmentId);
            List<String> lines = PaymentLines.lineIds(o);
            if (self || o.live() && !lines.isEmpty() && captured.containsAll(lines)) {
                out.add(o);
            }
        }
        return out;
    }

    private void settleShipment(Order order, Shipment s) {
        if (s.deliveredAt() != null) {
            payments.shipmentDelivered(order.id(), s.id(), s.deliveredAt());
        }
    }

    private void settle(Order order) {
        Instant deliveredAt = order.shipments().stream().map(Shipment::deliveredAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(Instant.now(clock));
        payments.delivered(order.id(), deliveredAt);
    }

    /** A pre-order whose cancellation could not be completed: staff are told, with the reason. */
    private void reportRefundFailure(FollowUpRepository.FollowUp task, RuntimeException e) {
        if (task.kind() != FollowUpRepository.Kind.CANCEL || task.shipmentId() == null) {
            return;
        }
        try {
            orders.findById(task.orderId()).ifPresent(order -> {
                Shipment s = order.shipment(task.shipmentId());
                if (s.preorderShipment()) {
                    tx.executeWithoutResult(status -> events.publish(OrdersEvents.PREORDER_REFUND_FAILED,
                            OrdersEvents.AGGREGATE, order.id(), OrdersEventPayloads.PreorderRefundFailed.of(order, s,
                                    e instanceof UpstreamFailure f ? f.code() : "orders.refund-failed", message(e),
                                    Instant.now(clock))));
                }
            });
        } catch (RuntimeException publishing) {
            LOG.error("Could not report the failed refund of {}", task.key(), publishing);
        }
    }

    private void cancelled(Order order, Shipment s) {
        cancelMoneyAndStock(order, s);
        if (s.preorderShipment()) {
            releasePreorderStock(order, s);
            tx.executeWithoutResult(status -> events.publish(OrdersEvents.PREORDER_REFUND_SETTLED,
                    OrdersEvents.AGGREGATE, order.id(),
                    OrdersEventPayloads.PreorderRefundSettled.of(order, s, Instant.now(clock))));
        }
    }

    /**
     * PREORDER packages are not inventory-tracked here (the window sells goods AfroTransact already holds), but if
     * inventory holds a per-line reservation for them it is released. Best effort: the money has been dealt with.
     */
    private void releasePreorderStock(Order order, Shipment s) {
        try {
            inventory.releaseGroups(order.id(), s.id(), PaymentLines.lineIds(s), "Pre-order cancelled");
        } catch (UpstreamFailure f) {
            LOG.info("No stock reservation released for pre-order shipment {} ({}); nothing to release", s.id(),
                    f.code());
        }
    }

    private void cancelMoneyAndStock(Order order, Shipment s) {
        String reason = "Shipment " + s.seq() + " of " + order.number() + " cancelled";
        if (s.captured()) {
            payments.refund(requirePayment(order), "refund-" + s.id(), PaymentLines.refundOf(s), reason);
            return;
        }
        boolean nothingLeft = order.shipments().stream().noneMatch(Shipment::live);
        if (nothingLeft) {
            releaseCoupon(order);
        }
        if (nothingLeft && order.paymentId() != null) {
            try {
                payments.cancel(order.paymentId(), reason);
            } catch (UpstreamFailure f) {
                if (f.status() != 409) {
                    throw f;
                }
                LOG.info("Payment of {} could not be cancelled ({}); captured shipments are refunded instead",
                        order.number(), f.code());
            }
        }
        if (s.fulfilmentType().inventoryTracked() && releasedOnItsOwn(order, s, reason)) {
            return;
        }
        if (s.fulfilmentType().inventoryTracked()) {
            boolean trackedLeft = order.shipments().stream()
                    .anyMatch(o -> o.live() && o.fulfilmentType().inventoryTracked());
            boolean committed = order.shipments().stream()
                    .anyMatch(o -> o.fulfilmentType().inventoryTracked() && o.status().handedOver());
            if (!trackedLeft && !committed) {
                try {
                    inventory.release(order.id(), reason);
                } catch (UpstreamFailure f) {
                    if (f.status() != 404) {
                        throw f;
                    }
                }
            } else if (!committed) {
                LOG.info("Shipment {} cancelled while other AfroTransact shipments of {} remain; its units stay held "
                        + "until the order ships (inventory has no partial release)", s.id(), order.number());
            }
        }
    }

    /**
     * Releases only the cancelled shipment's reservation lines (groups = its line ids). False when the reservation
     * predates per-line groups (inventory answers 404) so the order-level rules apply.
     */
    private boolean releasedOnItsOwn(Order order, Shipment s, String reason) {
        try {
            inventory.releaseGroups(order.id(), s.id(), PaymentLines.lineIds(s), reason);
            return true;
        } catch (UpstreamFailure f) {
            if (f.status() == 404) {
                return false;
            }
            throw f;
        }
    }

    /** Gives the coupon back when the whole order is cancelled (offers release is idempotent). */
    private void releaseCoupon(Order order) {
        orderCoupons.find(order.id()).filter(c -> !c.released()).ifPresent(c -> {
            try {
                coupons.release(c.redemptionId());
            } catch (UpstreamFailure f) {
                if (f.status() != 404) {
                    throw f;
                }
            }
            orderCoupons.markReleased(order.id(), Instant.now(clock));
        });
    }

    private static String requirePayment(Order order) {
        if (order.paymentId() == null) {
            throw new IllegalStateException("Order " + order.number() + " has no payment");
        }
        return order.paymentId();
    }

    private static String message(RuntimeException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 500 ? m.substring(0, 500) : m;
    }
}
