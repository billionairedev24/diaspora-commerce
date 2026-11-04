package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;

/**
 * An open run covering an address (S261 checkout, S263 targeting).
 *
 * @param run        the run
 * @param location   its location
 * @param ordersLeft orders still available (null = not limited)
 * @param full       no room for a one-package order (checkout offers the next run)
 * @param runUrl     storefront link
 * @param feeRule    the delivery fee rule behind the run's fee (ADR 0005 §3), or the run's own fee when staff set one
 *                   that differs from the rule's; checkout shows it and orders keeps it with the order
 */
public record AvailableRun(Run run, Location location, Integer ordersLeft, boolean full, String runUrl,
                           FeeSnapshot feeRule) {
}
