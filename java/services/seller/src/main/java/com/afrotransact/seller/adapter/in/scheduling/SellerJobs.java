package com.afrotransact.seller.adapter.in.scheduling;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.ComplianceService;
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

    SellerJobs(ComplianceService compliance, BillingService billing, UsageMeteringService metering) {
        this.compliance = compliance;
        this.billing = billing;
        this.metering = metering;
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
