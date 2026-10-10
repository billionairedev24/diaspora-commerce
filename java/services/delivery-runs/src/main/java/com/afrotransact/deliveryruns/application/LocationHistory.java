package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.port.MonthlyCount;
import com.afrotransact.deliveryruns.port.SubscriberCounts;

/** Per-location history (S265): runs with their orders, subscriber counts and monthly subscriber growth. */
public record LocationHistory(Location location, List<Run> runs, SubscriberCounts subscribers,
                              List<MonthlyCount> subscriberGrowth) {
}
