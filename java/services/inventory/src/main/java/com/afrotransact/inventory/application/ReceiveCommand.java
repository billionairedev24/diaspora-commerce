package com.afrotransact.inventory.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Lots from a completed goods receipt (sent by retail-service, or an FC receiver for FBA stock).
 *
 * @param receiptRef goods receipt number, for example {@code GRN-2610-0042}
 * @param ownerOrg   owner of the stock; null means AfroTransact Retail (1P)
 */
public record ReceiveCommand(String receiptRef, String nodeId, String ownerOrg, List<Line> lines) {

    public ReceiveCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One batch line.
     *
     * @param qtyGood             units passing inspection
     * @param qtyDamaged          units damaged on arrival (go to a separate {@code -DMG} quarantine lot)
     * @param unitLandedCostMinor FOB + freight, insurance, duty, fees per unit, in minor units
     * @param shelfLifeDays       product shelf life from manufacture (drives the short-dated rule)
     */
    public record Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                       String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays) {
    }
}
