package com.afrotransact.deliveryruns.application;

import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderStatus;

/**
 * One pre-order window in a location's history (S265).
 *
 * @param windowId           window
 * @param code               window code
 * @param name               window name
 * @param status             status now
 * @param deliversOn         delivery day
 * @param currency           ISO 4217
 * @param orders             orders that count (paid and late adds); zero once the window was cancelled
 * @param deliveryFeesMinor  delivery fees collected on them
 * @param revenueMinor       what those customers paid, delivery fees included
 * @param ordersRefunded     orders refunded in full by the window's cancellation
 * @param refundedMinor      what they got back
 * @param minOrders          the window's minimum (0 = none)
 * @param minimumMet         whether the orders placed reached the minimum (null without a minimum)
 */
public record WindowHistoryEntry(UUID windowId, String code, String name, PreorderStatus status, LocalDate deliversOn,
                                 String currency, int orders, long deliveryFeesMinor, long revenueMinor,
                                 int ordersRefunded, long refundedMinor, int minOrders, Boolean minimumMet) {
}
