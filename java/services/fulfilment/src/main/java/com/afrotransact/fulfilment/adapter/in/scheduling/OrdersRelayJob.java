package com.afrotransact.fulfilment.adapter.in.scheduling;

import com.afrotransact.fulfilment.application.OrdersRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries queued orders-service calls (transitions, courier events, tracking) with backoff. */
@Component
@ConditionalOnProperty(prefix = "at.fulfilment.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class OrdersRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersRelayJob.class);

    private final OrdersRelayService relay;
    private final int batch;

    OrdersRelayJob(OrdersRelayService relay, @Value("${at.fulfilment.jobs.relay-batch:50}") int batch) {
        this.relay = relay;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${at.fulfilment.jobs.relay-interval:5s}")
    void tick() {
        try {
            relay.drain(batch);
        } catch (RuntimeException e) {
            LOG.warn("orders relay run failed; next run retries", e);
        }
    }
}
