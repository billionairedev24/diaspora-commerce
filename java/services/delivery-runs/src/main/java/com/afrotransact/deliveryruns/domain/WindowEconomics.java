package com.afrotransact.deliveryruns.domain;

import java.util.UUID;

/**
 * A pre-order window's economics (S265): orders, revenue, delivery fees collected, the estimated cost of the run that
 * delivers it, the contribution margin and break-even orders; or, for a cancelled window, the orders refunded
 * instead of revenue.
 *
 * @param windowId             window
 * @param code                 window code
 * @param name                 window name
 * @param status               window status now
 * @param cancelled            the window was cancelled: orders were refunded, nothing was earned
 * @param locationId           the location its run is planned for (null when none serves it)
 * @param locationName         that location's name
 * @param currency             ISO 4217
 * @param minOrders            the staff-set minimum (0 = none)
 * @param orders               orders that count (paid and late adds); zero for a cancelled window
 * @param revenueMinor         what those customers paid, delivery fees included; zero for a cancelled window
 * @param deliveryFeesMinor    delivery fees collected on them
 * @param ordersRefunded       orders refunded in full by the cancellation (cancelled windows only)
 * @param refundsSettled       of those, refunds (or released authorisations) already confirmed by orders-service
 * @param refundedMinor        what customers got back, delivery fees included
 * @param economics            route, cost model, break-even and P&amp;L at the actual orders; null when no location
 *                             serves the window or geo-service cannot measure the route
 * @param minimumCoversBreakEven whether the minimum reaches break-even (null without a break-even or a minimum)
 * @param platformFees         the contribution lens: platform fees on the goods of these orders, apart from the
 *                            delivery P&amp;L above (null for a cancelled window)
 */
public record WindowEconomics(UUID windowId, String code, String name, PreorderStatus status, boolean cancelled,
                              UUID locationId, String locationName, String currency, int minOrders, int orders,
                              long revenueMinor, long deliveryFeesMinor, int ordersRefunded, int refundsSettled,
                              long refundedMinor, RunEconomics economics, Boolean minimumCoversBreakEven,
                              PlatformFees platformFees) {
}
