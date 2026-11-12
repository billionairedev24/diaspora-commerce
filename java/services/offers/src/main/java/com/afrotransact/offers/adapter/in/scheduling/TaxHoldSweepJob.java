package com.afrotransact.offers.adapter.in.scheduling;

import com.afrotransact.offers.application.TaxHoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net of the offer tax rule (S384): re-checks offers held for tax whose event was missed, stamps the live offers
 * that have a RESOLVED decision and holds the ones that have none (offers from before the check existed).
 */
@Component
class TaxHoldSweepJob {

    private static final Logger LOG = LoggerFactory.getLogger(TaxHoldSweepJob.class);

    private final TaxHoldService taxHold;

    TaxHoldSweepJob(TaxHoldService taxHold) {
        this.taxHold = taxHold;
    }

    @Scheduled(fixedDelayString = "${at.offers.tax.sweep-interval:60s}",
            initialDelayString = "${at.offers.tax.sweep-initial-delay:20s}")
    void run() {
        try {
            int changed = taxHold.sweep();
            if (changed > 0) {
                LOG.info("Tax release sweep changed {} offer(s)", changed);
            }
        } catch (RuntimeException ex) {
            LOG.warn("Tax release sweep failed; it runs again at the next interval", ex);
        }
    }
}
