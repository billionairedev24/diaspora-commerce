package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.OrdersClient;
import com.afrotransact.fulfilment.port.OrdersCommandQueue;
import com.afrotransact.fulfilment.port.OrdersCommandQueue.Command;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrdersRelayServiceImpl implements OrdersRelayService {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersRelayServiceImpl.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final OrdersClient orders;
    private final OrdersCommandQueue queue;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final Clock clock;
    private final int maxAttempts;

    OrdersRelayServiceImpl(OrdersClient orders, OrdersCommandQueue queue, DomainEventPublisher events,
                           DispatchFeed feed, Clock clock,
                           @Value("${at.fulfilment.relay.max-attempts:20}") int maxAttempts) {
        this.orders = orders;
        this.queue = queue;
        this.events = events;
        this.feed = feed;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void submit(List<Command> commands, String driverBearer) {
        Instant now = Instant.now(clock);
        for (Command c : commands) {
            if (queue.hasPending(c.shipmentId())) {
                queue.enqueue(c, now);
                continue;
            }
            try {
                call(c, driverBearer);
            } catch (UpstreamFailure f) {
                long id = queue.enqueue(c, now);
                if (retryable(f)) {
                    queue.retry(id, f.code() + ": " + f.getMessage(), now.plus(backoff(1)), now);
                } else {
                    queue.failed(id, f.code() + ": " + f.getMessage(), now);
                    forwardFailed(c, f, now);
                }
            }
        }
    }

    @Override
    public boolean deliverNow(Command c, String driverBearer) {
        Instant now = Instant.now(clock);
        if (queue.hasPending(c.shipmentId())) {
            queue.enqueue(c, now);
            return false;
        }
        try {
            call(c, driverBearer);
            return true;
        } catch (UpstreamFailure f) {
            if (!retryable(f)) {
                throw new FulfilmentRuleViolation(f.code(), f.getMessage());
            }
            long id = queue.enqueue(c, now);
            queue.retry(id, f.code() + ": " + f.getMessage(), now.plus(backoff(1)), now);
            return false;
        }
    }

    @Override
    @Transactional
    public int drain(int limit) {
        Instant now = Instant.now(clock);
        List<Command> due = queue.claimDue(limit, now);
        for (Command c : due) {
            try {
                call(c, null);
                queue.done(c.id(), now);
            } catch (UpstreamFailure f) {
                int attempts = c.attempts() + 1;
                if (retryable(f) && attempts < maxAttempts) {
                    queue.retry(c.id(), f.code() + ": " + f.getMessage(), now.plus(backoff(attempts)), now);
                } else {
                    LOG.warn("orders {} {} for shipment {} failed: {}", c.kind(), c.target(), c.shipmentId(),
                            f.getMessage());
                    queue.failed(c.id(), f.code() + ": " + f.getMessage(), now);
                    forwardFailed(c, f, now);
                }
            }
        }
        return due.size();
    }

    private void call(Command c, String driverBearer) {
        switch (c.kind()) {
            case "TRANSITION" -> orders.transition(c.shipmentId(), c.target(), c.detail(), c.idempotencyKey());
            case "COURIER" -> orders.courierEvent(c.shipmentId(), c.courier(), driverBearer);
            case "TRACKING" -> orders.attachTracking(c.shipmentId(), c.target(), c.detail());
            default -> throw new IllegalStateException("Unknown orders command " + c.kind());
        }
    }

    private void forwardFailed(Command c, UpstreamFailure f, Instant now) {
        events.publish(FulfilmentEvents.FORWARD_FAILED, FulfilmentEvents.AGGREGATE, c.shipmentId(),
                new FulfilmentEventPayloads.ForwardFailed(c.shipmentId(), c.kind(), c.target(), f.code(),
                        f.getMessage(), now));
        feed.publish(new DispatchFeed.Update("orders.forward_failed", null, null, "NORMAL",
                Map.of("shipmentId", c.shipmentId(), "kind", c.kind(), "target", String.valueOf(c.target()),
                        "code", String.valueOf(f.code()), "message", String.valueOf(f.getMessage())), now));
    }

    /**
     * Unreachable, 5xx, 408 and 429 are retried; so are 401 / 403 (token or scope problems, e.g. orders-service not
     * yet accepting relayed courier events from fulfilment-service) so a valid proof is never thrown away. Business
     * refusals (409 / 422, such as a wrong handover code) are final.
     */
    static boolean retryable(UpstreamFailure f) {
        return f.retryable() || f.status() == 401 || f.status() == 403;
    }

    static Duration backoff(int attempts) {
        Duration d = Duration.ofSeconds(5L * attempts * attempts);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
