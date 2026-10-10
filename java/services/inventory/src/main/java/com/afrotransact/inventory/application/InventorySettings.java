package com.afrotransact.inventory.application;

import java.time.Duration;

/**
 * Tunables of the inventory use cases ({@code at.inventory.*}).
 *
 * @param retailOrgId          owner organisation of AfroTransact Retail (1P) stock
 * @param reservationTtl       how long an unconfirmed checkout hold lives
 * @param fefoMinRemainingDays FEFO skips lots expiring within this many days
 * @param sweepBatch           reservations released per expiry sweep
 */
public record InventorySettings(String retailOrgId, Duration reservationTtl, int fefoMinRemainingDays,
                                int sweepBatch) {
}
