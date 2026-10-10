package com.afrotransact.taxreturns.adapter.in.jobs;

import com.afrotransact.taxreturns.application.ReturnsJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ReturnsJobService} on a fixed delay (off with {@code at.taxreturns.jobs.enabled=false}). Every step is
 * idempotent, so several replicas may run it; optimistic locking turns overlaps into skipped items.
 */
@Component
@ConditionalOnProperty(prefix = "at.taxreturns.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class ReturnsJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ReturnsJobs.class);

    private final ReturnsJobService jobs;

    ReturnsJobs(ReturnsJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${at.taxreturns.jobs.interval:60s}", initialDelayString = "30s")
    void tick() {
        try {
            ReturnsJobService.Report r = jobs.runOnce();
            if (r.autoApproved() + r.labels() + r.refunds() + r.stock() > 0) {
                LOG.info("Returns jobs: {} auto-approved, {} labels, {} refunds, {} stock postings", r.autoApproved(),
                        r.labels(), r.refunds(), r.stock());
            }
        } catch (RuntimeException ex) {
            LOG.warn("Returns jobs run failed: {}", ex.getMessage(), ex);
        }
    }
}
