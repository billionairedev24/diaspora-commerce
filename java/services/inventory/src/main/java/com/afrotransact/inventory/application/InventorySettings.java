package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Tunables of the inventory use cases ({@code at.inventory.*}).
 *
 * @param retailOrgId          owner organisation of AfroTransact Retail (1P) stock
 * @param reservationTtl       how long an unconfirmed checkout hold lives
 * @param fefoMinRemainingDays FEFO skips lots expiring within this many days
 * @param sweepBatch           reservations released per expiry sweep
 * @param defaultUnitCubicFeet storage volume of one unit whose SKU has no dimensions (FBA storage billing)
 * @param costCurrencies       currencies stock may be received in (ISO 4217, upper case); customers always pay in USD
 */
public record InventorySettings(String retailOrgId, Duration reservationTtl, int fefoMinRemainingDays,
                                int sweepBatch, BigDecimal defaultUnitCubicFeet, List<String> costCurrencies) {

    public InventorySettings {
        costCurrencies = costCurrencies == null ? List.of("USD") : List.copyOf(costCurrencies);
    }

    /** True when stock may be received and costed in {@code currency}. */
    public boolean acceptsCostCurrency(String currency) {
        return costCurrencies.contains(currency);
    }
}
