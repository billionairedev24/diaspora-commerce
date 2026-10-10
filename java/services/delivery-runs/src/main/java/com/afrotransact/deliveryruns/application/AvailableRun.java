package com.afrotransact.deliveryruns.application;

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
 */
public record AvailableRun(Run run, Location location, Integer ordersLeft, boolean full, String runUrl) {
}
