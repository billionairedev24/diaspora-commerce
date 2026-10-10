package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.UpstreamFailure;
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
 *       reservation when no AfroTransact-fulfilled shipment remains.</li>
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
    private final TransactionOperations tx;
    private final OrdersSettings settings;
    private final Clock clock;

    FollowUpServiceImpl(FollowUpRepository tasks, OrderRepository orders, PaymentsClient payments,
                        InventoryClient inventory, TransactionOperations tx, OrdersSettings settings, Clock clock) {
        this.tasks = tasks;
        this.orders = orders;
        this.payments = payments;
        this.inventory = inventory;
        this.tx = tx;
        this.settings = settings;
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
                Shipment current = locked.shipment(s.id());
                if (!current.captured()) {
                    orders.update(locked.withShipment(current.withCapture(capture.captureId(), Instant.now(clock))));
                }
            });
        }
        if (s.fulfilmentType().inventoryTracked()) {
            inventory.commit(order.id());
        }
    }

    private void settle(Order order) {
        Instant deliveredAt = order.shipments().stream().map(Shipment::deliveredAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(Instant.now(clock));
        payments.delivered(order.id(), deliveredAt);
    }

    private void cancelled(Order order, Shipment s) {
        String reason = "Shipment " + s.seq() + " of " + order.number() + " cancelled";
        if (s.captured()) {
            payments.refund(requirePayment(order), "refund-" + s.id(), PaymentLines.refundOf(s), reason);
            return;
        }
        boolean nothingLeft = order.shipments().stream().noneMatch(Shipment::live);
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
