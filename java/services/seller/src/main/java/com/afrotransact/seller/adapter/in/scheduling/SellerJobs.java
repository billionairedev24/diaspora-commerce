package com.afrotransact.seller.adapter.in.scheduling;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.seller.application.BillingAdjustmentService;
import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.ComplianceService;
import com.afrotransact.seller.application.ConnectedAccountSyncService;
import com.afrotransact.seller.application.EscalationService;
import com.afrotransact.seller.application.ChargeGateEvaluator;
import com.afrotransact.seller.application.EarningsCollectionService;
import com.afrotransact.seller.application.FoodServiceMigrationService;
import com.afrotransact.seller.application.InvoiceCollectionService;
import com.afrotransact.seller.application.RewardService;
import com.afrotransact.seller.application.SellingApprovalService;
import com.afrotransact.seller.application.SellingReviewService;
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
    private final ConnectedAccountSyncService accountSync;
    private final EscalationService escalation;
    private final SellingApprovalService selling;
    private final SellingReviewService sellingReview;
    private final FoodServiceMigrationService foodService;
    private final Clock clock;

    SellerJobs(ComplianceService compliance, BillingService billing, UsageMeteringService metering,
               RewardService rewards, BillingAdjustmentService adjustments, EarningsCollectionService earnings,
               InvoiceCollectionService collection, ChargeGateEvaluator gates,
               ConnectedAccountSyncService accountSync, EscalationService escalation,
               SellingApprovalService selling, SellingReviewService sellingReview,
               FoodServiceMigrationService foodService, Clock clock) {
        this.compliance = compliance;
        this.billing = billing;
        this.metering = metering;
        this.rewards = rewards;
        this.adjustments = adjustments;
        this.earnings = earnings;
        this.collection = collection;
        this.gates = gates;
        this.accountSync = accountSync;
        this.escalation = escalation;
        this.selling = selling;
        this.sellingReview = sellingReview;
        this.foodService = foodService;
        this.clock = clock;
    }

    /**
     * Application review: raises the escalation level of cases that are overdue or that nobody has taken. Each case is
     * checked under its application's row lock and only ever moves up one level, so replicas running at once (and
     * re-runs) escalate once.
     */
    @Scheduled(fixedDelayString = "${at.seller.jobs.escalation-interval:5m}",
            initialDelayString = "${at.seller.jobs.escalation-interval:5m}")
    void applicationEscalations() {
        run("application-escalations", () -> {
            int raised = escalation.runDue();
            if (raised > 0) {
                LOG.info("Escalated {} application(s)", raised);
            }
        });
    }

    /** Selling approvals: the same escalation as applications, for the "Selling approval" case type. */
    @Scheduled(fixedDelayString = "${at.seller.jobs.escalation-interval:5m}",
            initialDelayString = "${at.seller.jobs.escalation-interval:5m}")
    void sellingEscalations() {
        run("selling-escalations", () -> {
            int raised = sellingReview.runDueEscalations();
            if (raised > 0) {
                LOG.info("Escalated {} selling approval case(s)", raised);
            }
        });
    }

    /**
     * Selling approval lifecycle, hourly: warnings 30, 14 and 7 days before a document expires, expiry, and after 14
     * days of grace the scope's listings are paused (never deleted). Each approval is checked under its row lock and
     * stamps what it did, so replicas running at once and re-runs warn, expire and pause once.
     */
    @Scheduled(cron = "${at.seller.jobs.selling-lifecycle-cron:0 10 * * * *}", zone = "UTC")
    void sellingLifecycle() {
        run("selling-lifecycle", () -> {
            int changed = selling.runLifecycle(Instant.now(clock));
            if (changed > 0) {
                LOG.info("Selling approval lifecycle changed {} approval(s)", changed);
            }
        });
    }

    /** D11: bakery answers become PENDING food service applications for staff (idempotent, never approves). */
    @Scheduled(fixedDelayString = "${at.seller.jobs.food-service-migration-interval:6h}",
            initialDelayString = "${at.seller.jobs.food-service-migration-initial-delay:2m}")
    void foodServiceMigration() {
        run("food-service-migration", foodService::run);
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

    /**
     * Tells payments about every connected account: without it a seller's earnings stay on hold. Upsert, so repeatable.
     * Nightly by default; the local profile runs it every minute, and staff can start it from the console.
     */
    @Scheduled(cron = "${at.seller.jobs.account-sync-cron:0 30 2 * * *}", zone = "UTC")
    void connectedAccountSync() {
        run("connected-account-sync", () -> LOG.debug("Registered {} Connect account(s) with payments",
                accountSync.registerAll()));
    }

    private static void run(String job, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            LOG.error("Seller job {} failed; it will run again on schedule", job, e);
        }
    }
}
