package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrderTally;
import com.afrotransact.deliveryruns.domain.RunPnl;

/**
 * One run in a location's history (S265): the run, its orders counted the way the run P&amp;L counts them, and its
 * P&amp;L (null when the route could not be measured, so no cost is known).
 *
 * @param run   the run
 * @param tally the run's orders (delivered, cancelled, refunded, delivery fees)
 * @param pnl   the run's P&amp;L, the same figures {@code GET /v1/runs/{id}/pnl} answers
 */
public record RunHistoryEntry(Run run, RunOrderTally tally, RunPnl pnl) {

    /** Whether the run's minimum reaches the break-even at its own fee (null without a break-even). */
    public Boolean minimumCoveredBreakEven() {
        if (pnl == null || pnl.economics().breakEvenOrders() == null) {
            return null;
        }
        return run.terms().minOrders() >= pnl.economics().breakEvenOrders();
    }
}
