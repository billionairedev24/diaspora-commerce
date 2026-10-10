package com.afrotransact.retail.port;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Local calendar of the receiving warehouse (S226): receipt date rules (made ≤ today, expiry not passed, remaining
 * life, on-time delivery) use the warehouse's own date, not UTC.
 */
public interface WarehouseCalendar {

    /** Time zone of the node (inventory-service node id such as {@code HOU-FC}); never fails. */
    ZoneId zoneOf(String nodeId);

    /** Today's date at the node. */
    LocalDate today(String nodeId);
}
