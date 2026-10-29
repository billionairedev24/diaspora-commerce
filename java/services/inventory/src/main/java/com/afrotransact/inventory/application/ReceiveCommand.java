package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.domain.CostComponents;

/**
 * Lots from a completed goods receipt (sent by retail-service, or an FC receiver for FBA stock).
 *
 * @param receiptRef goods receipt number, for example {@code GRN-2610-0042}
 * @param ownerOrg   owner of the stock; null means AfroTransact Retail (1P)
 * @param inboundRef the inbound shipment the units arrived on (FBA plan number or retail shipment reference): its
 *                   expected units are consumed and the received quantities recorded against the plan (S226, S257);
 *                   null or unknown = units are taken off the position's inbound as before
 */
public record ReceiveCommand(String receiptRef, String nodeId, String ownerOrg, String inboundRef, List<Line> lines) {

    public ReceiveCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A receipt without inbound shipment. */
    public ReceiveCommand(String receiptRef, String nodeId, String ownerOrg, List<Line> lines) {
        this(receiptRef, nodeId, ownerOrg, null, lines);
    }

    /**
     * One batch line.
     *
     * @param qtyGood             units passing inspection
     * @param qtyDamaged          units damaged on arrival (go to a separate {@code -DMG} quarantine lot)
     * @param unitLandedCostMinor FOB + freight, insurance, duty, fees per unit, in minor units of {@code currency}
     * @param currency            the currency actually paid (USD, MXN, NGN, GHS, …: a configured list)
     * @param shelfLifeDays       product shelf life from manufacture (drives the short-dated rule)
     * @param holdReason          receive the good units straight into QUARANTINE with this reason (e.g. a QC hold
     *                            decided at the dock), so no separate quarantine call is needed; null = sellable
     * @param fxRateToUsd         US dollars per 1 unit of {@code currency}, as entered by the receiver; null = the
     *                            latest rate on or before the receiving date in the staff-managed table (US dollars
     *                            need none: rate 1)
     * @param fxRateDate          the day the entered rate is for; null = the receiving date
     * @param unitCostUsdMinor    unit cost in US cents when the sender already valued it (retail-service books landed
     *                            cost in USD and must not differ by rounding); null = unit cost × rate
     * @param costComponents      goods, freight, duty and other per unit in {@code currency} (must add up to the unit
     *                            cost); null = all goods
     */
    public record Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                       String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays,
                       String holdReason, BigDecimal fxRateToUsd, LocalDate fxRateDate, Long unitCostUsdMinor,
                       CostComponents costComponents) {

        /** A line costed at the table's rate. */
        public Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                    String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays,
                    String holdReason) {
            this(sku, lotNo, mfgDate, expDate, qtyGood, qtyDamaged, bin, unitLandedCostMinor, currency, shelfLifeDays,
                    holdReason, null, null, null, null);
        }

        /** A line without hold reason. */
        public Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                    String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays) {
            this(sku, lotNo, mfgDate, expDate, qtyGood, qtyDamaged, bin, unitLandedCostMinor, currency, shelfLifeDays,
                    null);
        }
    }
}
