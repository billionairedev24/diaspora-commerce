package com.afrotransact.deliveryruns.adapter.in.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.PreorderSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code at.delivery-runs.jobs.interval}: pre-order comms due ({@code preorder.comms.due}), opened / closed /
 * delivered events and the hand-over of closed windows to runs; hourly, the 90-day PII retention. Each window is
 * processed in its own transaction under {@code FOR UPDATE SKIP LOCKED}; a failing one is retried next tick.
 */
@Component
@ConditionalOnProperty(prefix = "at.delivery-runs.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PreorderScheduleJob {

    private static final Logger LOG = LoggerFactory.getLogger(PreorderScheduleJob.class);
    private static final Duration RETENTION_EVERY = Duration.ofHours(1);

    private final PreorderSchedulerService scheduler;
    private final int batchSize;
    private final Clock clock;
    private volatile Instant lastPurge = Instant.EPOCH;

    PreorderScheduleJob(PreorderSchedulerService scheduler,
                        @Value("${at.delivery-runs.jobs.batch-size:50}") int batchSize, Clock clock) {
        this.scheduler = scheduler;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${at.delivery-runs.jobs.interval:60s}",
            initialDelayString = "${at.delivery-runs.jobs.initial-delay:15s}")
    public void tick() {
        int steps = 0;
        for (UUID id : scheduler.candidates(batchSize)) {
            try {
                steps += scheduler.process(id);
            } catch (RuntimeException e) {
                LOG.warn("Pre-order schedule failed for window {}; retrying next tick", id, e);
            }
        }
        if (steps > 0) {
            LOG.info("Pre-order schedule: {} step(s) done", steps);
        }
        Instant now = Instant.now(clock);
        if (now.isAfter(lastPurge.plus(RETENTION_EVERY))) {
            lastPurge = now;
            try {
                scheduler.purgeExpired();
            } catch (RuntimeException e) {
                LOG.warn("Pre-order retention failed; retrying later", e);
            }
        }
    }
}
