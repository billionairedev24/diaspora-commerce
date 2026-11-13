package com.afrotransact.taxreturns.adapter.in.jobs;

import com.afrotransact.taxreturns.application.PendingReviewSweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Approves the records waiting in tax review that the risk-based rule decides, shortly after start-up and then every
 * {@code at.taxreturns.jobs.review-sweep-interval} (off with {@code at.taxreturns.jobs.enabled=false}). Idempotent, so
 * several replicas may run it.
 */
@Component
@ConditionalOnProperty(prefix = "at.taxreturns.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class ReviewSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewSweepJob.class);

    private final PendingReviewSweepService sweep;

    ReviewSweepJob(PendingReviewSweepService sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${at.taxreturns.jobs.review-sweep-interval:5m}", initialDelayString = "20s")
    void tick() {
        try {
            sweep.sweep();
        } catch (RuntimeException ex) {
            LOG.warn("Review sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
