package com.afrotransact.trustops.adapter.in.scheduling;

import com.afrotransact.trustops.application.SellerHealthService;
import com.afrotransact.trustops.application.SlaMaintenanceService;
import com.afrotransact.trustops.application.SlaSweepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** SLA sweep (every minute by default) and the daily seller-health computation (S223). */
@Component
@ConditionalOnProperty(prefix = "at.trustops.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class TrustOpsJobs {

    private static final Logger LOG = LoggerFactory.getLogger(TrustOpsJobs.class);

    private final SlaMaintenanceService sla;
    private final SellerHealthService health;

    TrustOpsJobs(SlaMaintenanceService sla, SellerHealthService health) {
        this.sla = sla;
        this.health = health;
    }

    @Scheduled(fixedDelayString = "${at.trustops.jobs.sla-interval:60s}",
            initialDelayString = "${at.trustops.jobs.sla-interval:60s}")
    void slaSweep() {
        try {
            SlaSweepResult r = sla.sweep();
            if (r.listingsEscalated() + r.sellerRemindersSent() + r.sellerWindowsLapsed() + r.claimsEscalated() > 0) {
                LOG.info("SLA sweep: {} listings escalated, {} seller reminders, {} seller windows lapsed, "
                        + "{} claims escalated", r.listingsEscalated(), r.sellerRemindersSent(),
                        r.sellerWindowsLapsed(), r.claimsEscalated());
            }
        } catch (RuntimeException e) {
            LOG.warn("SLA sweep failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${at.trustops.seller-health.cron:0 15 3 * * *}", zone = "UTC")
    void sellerHealth() {
        LOG.info("Seller health computed for {} sellers", health.computeAll());
    }
}
