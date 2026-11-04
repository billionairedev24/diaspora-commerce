package com.afrotransact.platform.events.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Triggers {@link EventRetentionService#purge()} on {@code at.events.retention.cron}; a failure never escapes. */
class EventRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(EventRetentionJob.class);

    private final EventRetentionService retention;

    EventRetentionJob(EventRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${at.events.retention.cron:0 30 3 * * *}")
    void run() {
        try {
            retention.purge();
        } catch (RuntimeException ex) {
            LOG.error("Event retention failed; it will run again at the next scheduled time", ex);
        }
    }
}
