package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.port.MonthlyCount;
import com.afrotransact.deliveryruns.port.NetGrowth;
import com.afrotransact.deliveryruns.port.SubscriberCounts;

/**
 * Per-location history (S265): runs with their orders, cost, margin and break-even, pre-order windows with their
 * orders and fees, subscriber counts and monthly subscriber growth (gross sign-ups, and sign-ups minus unsubscribes).
 */
public record LocationHistory(Location location, List<RunHistoryEntry> runs, List<WindowHistoryEntry> windows,
                              SubscriberCounts subscribers, List<MonthlyCount> subscriberGrowth,
                              List<NetGrowth> subscriberNetGrowth) {
}
