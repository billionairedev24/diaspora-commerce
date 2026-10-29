package com.afrotransact.deliveryruns.domain;

import java.util.UUID;

/**
 * Break-even for a pre-order window being created or edited (S265), shown next to its minimum order count.
 *
 * @param locationId             the location the run would be planned for (null when none serves the targets)
 * @param locationName           its name
 * @param currency               ISO 4217
 * @param feeMinor               the delivery fee per order the plan assumes (the window's own fee, else the fee
 *                               rule for the location's distance, else the location's default)
 * @param dropMode               doorstep or pickup point
 * @param minOrders              the minimum asked about
 * @param economics              route, cost, break-even and P&amp;L at the minimum and at the expected orders; null
 *                               when no location serves the targets or geo-service cannot measure the route
 * @param minimumCoversBreakEven whether the minimum reaches break-even (null without a break-even or a minimum)
 */
public record WindowPlan(UUID locationId, String locationName, String currency, long feeMinor, DropMode dropMode,
                         int minOrders, RunEconomics economics, Boolean minimumCoversBreakEven) {
}
