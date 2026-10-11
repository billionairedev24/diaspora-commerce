package com.afrotransact.seller.adapter.in.scheduling;

import com.afrotransact.seller.application.PlanSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reconciles the plan catalogue with Stripe once the service is ready (S292): adopts Prices made by
 * {@code make stripe-setup}, creates missing ones and replaces drifted ones. Errors are recorded per price and never
 * stop the service; "Sync with Stripe" in the staff console runs the same reconcile on demand.
 */
@Component
@ConditionalOnProperty(prefix = "at.seller.billing", name = "sync-on-startup", havingValue = "true",
        matchIfMissing = true)
class PlanCatalogStartupSync {

    private static final Logger LOG = LoggerFactory.getLogger(PlanCatalogStartupSync.class);

    private final PlanSyncService sync;

    PlanCatalogStartupSync(PlanSyncService sync) {
        this.sync = sync;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reconcile() {
        try {
            sync.reconcile();
        } catch (RuntimeException e) {
            LOG.warn("Plan catalogue sync with Stripe failed at startup; staff can run it from the console", e);
        }
    }
}
