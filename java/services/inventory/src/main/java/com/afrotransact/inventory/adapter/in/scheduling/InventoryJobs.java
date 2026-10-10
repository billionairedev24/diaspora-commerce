package com.afrotransact.inventory.adapter.in.scheduling;

import com.afrotransact.inventory.application.InventorySettings;
import com.afrotransact.inventory.application.LotLifecycleService;
import com.afrotransact.inventory.application.ReconciliationService;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled inbound adapter. Every job is idempotent and safe on several replicas: the reservation sweep uses
 * {@code FOR UPDATE SKIP LOCKED}, lot expiry re-checks each lot under its position lock, reconciliation is read-only.
 */
@Component
@ConditionalOnProperty(prefix = "at.inventory.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class InventoryJobs {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryJobs.class);

    private final ReservationService reservations;
    private final LotLifecycleService lots;
    private final ReconciliationService reconciliation;
    private final StorageService storage;
    private final InventorySettings settings;

    InventoryJobs(ReservationService reservations, LotLifecycleService lots, ReconciliationService reconciliation,
                  StorageService storage, InventorySettings settings) {
        this.reservations = reservations;
        this.lots = lots;
        this.reconciliation = reconciliation;
        this.storage = storage;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${at.inventory.jobs.reservation-sweep-interval:30s}",
            initialDelayString = "${at.inventory.jobs.reservation-sweep-interval:30s}")
    void releaseExpiredReservations() {
        run("reservation-expiry", () -> {
            int released;
            do {
                released = reservations.expireDue(settings.sweepBatch());
            } while (released == settings.sweepBatch());
        });
    }

    @Scheduled(cron = "${at.inventory.jobs.expire-lots-cron:0 5 * * * *}", zone = "${at.inventory.jobs.zone:UTC}")
    void expireLots() {
        run("lot-expiry", lots::expireDueLots);
    }

    @Scheduled(cron = "${at.inventory.jobs.markdown-cron:0 20 2 * * *}", zone = "${at.inventory.jobs.zone:UTC}")
    void flagMarkdown() {
        run("markdown-flags", lots::refreshMarkdownFlags);
    }

    @Scheduled(cron = "${at.inventory.jobs.reconciliation-cron:0 40 2 * * *}", zone = "${at.inventory.jobs.zone:UTC}")
    void reconcile() {
        run("reconciliation", reconciliation::reconcile);
    }

    @Scheduled(cron = "${at.inventory.jobs.storage-snapshot-cron:0 55 * * * *}", zone = "UTC")
    void snapshotStorage() {
        run("storage-snapshot", storage::snapshotTodayAsJob);
    }

    private static void run(String job, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            LOG.error("Inventory job {} failed; it will run again on schedule", job, e);
        }
    }
}
