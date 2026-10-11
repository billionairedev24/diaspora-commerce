package com.afrotransact.seller.adapter.in.scheduling;

import com.afrotransact.seller.application.BillingAdjustmentService;
import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.ComplianceService;
import com.afrotransact.seller.application.EarningsCollectionService;
import com.afrotransact.seller.application.RewardService;
import com.afrotransact.seller.application.UsageMeteringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled inbound adapter. Every job is idempotent and safe on several replicas: reminders are claimed once per
 * (account, deadline, threshold) by a unique index; plan changes and activations re-check state under a row lock.
 */
@Component
@ConditionalOnProperty(prefix = "at.seller.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class SellerJobs {

    private static final Logger LOG = LoggerFactory.getLogger(SellerJobs.class);

    private final ComplianceService compliance;
    private final BillingService billing;
    private final UsageMeteringService metering;
    private final RewardService rewards;
    private final BillingAdjustmentService adjustments;
    private final EarningsCollectionService earnings;

    SellerJobs(ComplianceService compliance, BillingService billing, UsageMeteringService metering,
               RewardService rewards, BillingAdjustmentService adjustments, EarningsCollectionService earnings) {
        this.compliance = compliance;
        this.billing = billing;
        this.metering = metering;
        this.rewards = rewards;
        this.adjustments = adjustments;
        this.earnings = earnings;
    }

    /**
     * S296: every live reward rule for every organisation, daily (days since approval and windows have no events);
     * grants are unique per (rule, organisation), so replicas running at once grant once.
     */
    @Scheduled(cron = "${at.seller.jobs.rewards-cron:0 40 2 * * *}", zone = "UTC")
    void rewards() {
        run("rewards", () -> LOG.info("Daily reward evaluation granted {} reward(s)", rewards.evaluateAll()));
    }

    /** Grants recorded but not applied at Stripe yet (Stripe was unavailable): retried with their own keys. */
    @Scheduled(fixedDelayString = "${at.seller.jobs.grant-retry-interval:15m}",
            initialDelayString = "${at.seller.jobs.grant-retry-interval:15m}")
    void retryGrants() {
        run("grant-retry", adjustments::retryPending);
    }

    /** Collection from earnings (no-op while the flag is off): renewal invoices claimed from invoice.created. */
    @Scheduled(fixedDelayString = "${at.seller.jobs.earnings-interval:5m}",
            initialDelayString = "${at.seller.jobs.earnings-interval:5m}")
    void earningsCollection() {
        run("earnings-collection", earnings::processOpen);
    }

    /** S206: meters the two previous UTC days; a replica that finds the lease taken skips (seller.usage-run-…). */
    @Scheduled(cron = "${at.seller.jobs.metering-cron:0 20 1 * * *}", zone = "UTC")
    void metering() {
        run("metering", metering::runScheduled);
    }

    @Scheduled(cron = "${at.seller.jobs.reminder-cron:0 15 * * * *}", zone = "${at.seller.jobs.zone:UTC}")
    void complianceReminders() {
        run("compliance-reminders", () -> LOG.info("Sent {} compliance reminders", compliance.sendDueReminders()));
    }

    @Scheduled(cron = "${at.seller.jobs.plan-change-cron:0 5 * * * *}", zone = "${at.seller.jobs.zone:UTC}")
    void pendingPlanChanges() {
        run("plan-changes", () -> LOG.info("Applied {} pending plan changes", billing.applyDueChanges()));
    }

    @Scheduled(fixedDelayString = "${at.seller.jobs.activation-retry-interval:10m}",
            initialDelayString = "${at.seller.jobs.activation-retry-interval:10m}")
    void retryActivations() {
        run("subscription-activation", billing::retryPendingActivations);
    }

    private static void run(String job, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            LOG.error("Seller job {} failed; it will run again on schedule", job, e);
        }
    }
}
