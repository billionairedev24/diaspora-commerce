package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.PriceSyncState;

/**
 * The drift report ("Sync with Stripe").
 *
 * @param applied whether the sync ran (false for the preview)
 */
public record PlanSyncReport(Instant checkedAt, boolean applied, List<Line> lines) {

    public PlanSyncReport {
        lines = List.copyOf(lines);
    }

    public long drifted() {
        return lines.stream().filter(l -> l.state() == PriceSyncState.DRIFT || l.state() == PriceSyncState.MISSING
                || l.state() == PriceSyncState.ADOPTABLE).count();
    }

    /**
     * One plan version price.
     *
     * @param state        before the sync
     * @param action       what the sync did: NONE, ADOPTED, CREATED, REPLACED (new Price, old one archived), FAILED
     * @param stripePriceId the Price id stored afterwards
     * @param stripeAmountCents what Stripe's Price charges (null when there is none)
     */
    public record Line(String planCode, String planName, int version, Cadence cadence, long amountCents,
                       String lookupKey, PriceSyncState state, String action, String stripePriceId,
                       Long stripeAmountCents, String error) {
    }
}
