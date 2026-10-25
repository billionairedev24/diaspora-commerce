package com.afrotransact.deliveryruns.application;

import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunPnl;
import com.afrotransact.deliveryruns.domain.WindowEconomics;
import com.afrotransact.deliveryruns.domain.WindowPlan;

/**
 * Run and window economics for staff (S265, {@code delivery-runs:read}): the P&amp;L before and after a run, a window's
 * orders, revenue, fees and cost, the break-even to weigh a minimum against, and, for what was cancelled, the orders
 * refunded instead of revenue. The driver cost model is {@link EconomicsSettingsService}.
 */
public interface EconomicsService {

    /**
     * A run's P&amp;L: at its reserved orders before it is driven, at its orders once completed; a cancelled run reports the
     * orders it refunded and earns and costs nothing. 502 {@code delivery-runs.geo-unavailable} when the route is unknown.
     */
    RunPnl runPnl(UUID runId, Long commissionShareMinor);

    /** A window's economics at its actual orders (a cancelled one: orders refunded, no revenue). */
    WindowEconomics windowEconomics(UUID windowId, Long commissionShareMinor);

    /** Break-even and P&amp;L for a window being created or edited, shown beside its minimum order count. */
    WindowPlan planWindow(WindowPlanCommand command);
}
