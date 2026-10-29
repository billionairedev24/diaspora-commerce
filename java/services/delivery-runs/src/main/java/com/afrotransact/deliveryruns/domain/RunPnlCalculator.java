package com.afrotransact.deliveryruns.domain;

import java.util.List;

/** Builds a run's P&amp;L from the run and its real orders (S265). */
public interface RunPnlCalculator {

    /**
     * The P&amp;L of a run.
     *
     * @param run                     the run
     * @param route                   the route to price it with
     * @param slots                   the run's slots, whatever their status
     * @param manualPlatformFeesMinor platform fees on goods typed in by staff for this view (null = use the data)
     */
    RunPnl pnl(Run run, RouteEstimate route, List<RunOrder> slots, Long manualPlatformFeesMinor);
}
