package com.afrotransact.identity.adapter.in.scheduling;

import com.afrotransact.identity.application.OrgReconcileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles identity's copy of seller and provider organisations with the seller service shortly after start-up
 * and then every few minutes ({@code at.identity.seller-sync.*}): an approval event identity missed or got late
 * (identity down, a failed delivery, an old event arriving after a newer one) is repaired without anyone noticing.
 */
@Component
@ConditionalOnProperty(prefix = "at.identity.seller-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
class OrgReconcileJob {

    private static final Logger LOG = LoggerFactory.getLogger(OrgReconcileJob.class);

    private final OrgReconcileService reconcile;

    OrgReconcileJob(OrgReconcileService reconcile) {
        this.reconcile = reconcile;
    }

    @Scheduled(initialDelayString = "${at.identity.seller-sync.initial-delay:30s}",
            fixedDelayString = "${at.identity.seller-sync.interval:5m}")
    void run() {
        try {
            reconcile.reconcileAll();
        } catch (RuntimeException ex) {
            LOG.warn("Organisation reconcile skipped: the seller service could not be read ({})", ex.getMessage());
        }
    }
}
