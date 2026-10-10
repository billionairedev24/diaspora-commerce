package com.afrotransact.retail.port;

import java.time.LocalDate;
import java.util.List;

/**
 * Lots of a completed goods receipt for inventory-service {@code POST /v1/receipts}. {@code ownerOrg} null means
 * AfroTransact Retail (1P).
 *
 * @param inboundRef the inbound shipment reference (retail shipment / ASN) the units arrived on, so inventory turns
 *                   exactly that notice's expected units into stock (S226); null for receipts without a shipment
 */
public record InventoryReceipt(String receiptRef, String nodeId, String ownerOrg, String inboundRef,
                               List<Line> lines) {

    public InventoryReceipt {
        lines = List.copyOf(lines);
    }

    /** A receipt that did not arrive on an inbound shipment. */
    public InventoryReceipt(String receiptRef, String nodeId, String ownerOrg, List<Line> lines) {
        this(receiptRef, nodeId, ownerOrg, null, lines);
    }

    /**
     * One batch: good units become a lot, damaged units a separate -DMG quarantine lot.
     *
     * @param holdReason the good units go straight into QUARANTINE with this reason (QA failed at the dock), so no
     *                   separate quarantine call is needed (S226); null = sellable
     */
    public record Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                       String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays,
                       String holdReason) {

        /** A sellable batch. */
        public Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                    String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays) {
            this(sku, lotNo, mfgDate, expDate, qtyGood, qtyDamaged, bin, unitLandedCostMinor, currency, shelfLifeDays,
                    null);
        }
    }
}
