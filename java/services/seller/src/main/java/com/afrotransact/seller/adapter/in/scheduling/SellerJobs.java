package com.afrotransact.seller.adapter.in.scheduling;

import com.afrotransact.seller.application.BillingAdjustmentService;
import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.ComplianceService;
import com.afrotransact.seller.application.ChargeGateEvaluator;
import com.afrotransact.seller.application.EarningsCollectionService;
import com.afrotransact.seller.application.InvoiceCollectionService;
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
    private final InvoiceCollectionService collection;
    private final ChargeGateEvaluator gates;

    SellerJobs(ComplianceService compliance, BillingService billing, UsageMeteringService metering,
               RewardService rewards, BillingAdjustmentService adjustments, EarningsCollectionService earnings,
               InvoiceCollectionService collection, ChargeGateEvaluator gates) {
        this.compliance = compliance;
        this.billing = billing;
        this.metering = metering;
        this.rewards = rewards;
        this.adjustments = adjustments;
        this.earnings = earnings;
        this.collection = collection;
        this.gates = gates;
    }

    /**
     * S296: every live reward rule for every organisation, daily (days since approval and windows have no events);
     * grants are unique per (rule, organisation), so replicas running at once grant once.
     */
    @Scheduled(cron = "${at.seller.jobs.rewards-cron:0 40 2 * * *}", zone = "UTC")
    void rewards() {
        run("rewards", () -> LOG.info("Daily reward evaluation granted {} reward(s)", rewards.evaluateAll()));
    }

    /**
     * S299: rules checked at window end ("less than $1,000 in the first 30 days"), decided once per (rule,
     * organisation, window) when the check is due: the rule's lead (default 24 h) before the window or the trial ends,
     * so a free period moves the first charge out before the invoice is issued. Unique checks make replicas decide once.
     */
    @Scheduled(fixedDelayString = "${at.seller.jobs.reward-window-interval:15m}",
            initialDelayString = "${at.seller.jobs.reward-window-interval:15m}")
    void rewardWindowEnds() {
        run("reward-window-ends", () -> {
            int made = rewards.evaluateWindowEnds();
            if (made > 0) {
                LOG.info("Window-end reward checks granted {} reward(s)", made);
            }
        });
    }

    /** Grants recorded but not applied yet: retried. */
    @Scheduled(fixedDelayString = "${at.seller.jobs.grant-retry-interval:15m}",
            initialDelayString = "${at.seller.jobs.grant-retry-interval:15m}")
    void retryGrants() {
        run("grant-retry", adjustments::retryPending);
    }

    /** Open plan invoices whose next attempt is due: platform credit, earnings if chosen, then the saved card. */
    @Scheduled(fixedDelayString = "${at.seller.jobs.collection-interval:10m}",
            initialDelayString = "${at.seller.jobs.collection-interval:10m}")
    void invoiceCollection() {
        run("invoice-collection", () -> LOG.info("Collected {} due plan invoice(s)", collection.collectDue()));
    }

    /** Releases of earnings withheld for plan fees whose invoice was voided (no-op while the flag is off). */
    @Scheduled(fixedDelayString = "${at.seller.jobs.earnings-interval:5m}",
            initialDelayString = "${at.seller.jobs.earnings-interval:5m}")
    void earningsReleases() {
        run("earnings-releases", earnings::processReleases);
    }

    /** Rolls subscriptions whose period ended into the next one, issuing and collecting its invoice. */
    @Scheduled(fixedDelayString = "${at.seller.jobs.renewal-interval:5m}",
            initialDelayString = "${at.seller.jobs.renewal-interval:5m}")
    void renewals() {
        run("renewals", () -> LOG.info("Renewed {} subscription(s)", billing.renewDueSubscriptions()));
    }

    /** Marks charge gates ("start charging after X sales or X listings") the seller has reached. */
    @Scheduled(cron = "${at.seller.jobs.gates-cron:0 50 2 * * *}", zone = "UTC")
    void chargeGates() {
        run("charge-gates", () -> LOG.info("{} charge gate(s) reached", gates.markMetGates()));
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
