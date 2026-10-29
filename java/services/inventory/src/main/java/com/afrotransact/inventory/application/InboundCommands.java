package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.PostalAddress;

/** Inputs of the inbound shipment use cases (S257). */
public final class InboundCommands {

    private InboundCommands() {
    }

    /**
     * A seller's "Send stock to AfroTransact" plan.
     *
     * @param ownerOrg     staff only: the seller organisation (sellers always plan for their own)
     * @param nodeId       requested fulfilment centre, or null to have one assigned
     * @param storageZone  storage the units need (Ambient, Chilled …), or null
     */
    public record CreatePlan(String ownerOrg, String name, String nodeId, String storageZone, PostalAddress shipFrom,
                             LocalDate expectedArrival, List<PlanLine> lines, List<BoxInput> boxes) {

        public CreatePlan {
            lines = lines == null ? List.of() : List.copyOf(lines);
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
        }
    }

    /**
     * A SKU and quantity to send, with the packing-list details of the batch.
     *
     * @param atin     catalog variant ATIN (required: unit labels and the Fulfilled-by-AfroTransact offer)
     * @param sku      inventory SKU the units are stocked under (the offer's inventory SKU); default = the seller's
     *                 AT-FN unit label code, unique per seller and variant so stock never mixes
     * @param casePack units per case (qty must be whole cases), or null
     * @param lotNo    the batch on the packing list, or null
     * @param mfgDate  manufacture date of the batch, or null
     * @param expDate  expiry / best-before date of the batch, or null
     */
    public record PlanLine(String atin, String sku, int qty, Integer casePack, String lotNo, LocalDate mfgDate,
                           LocalDate expDate) {

        /** A line without case pack or batch details. */
        public PlanLine(String atin, String sku, int qty) {
            this(atin, sku, qty, null, null, null, null);
        }
    }

    /** One carton and its contents (units per SKU). */
    public record BoxInput(List<BoxItem> contents, BigDecimal weightLb, BigDecimal lengthIn, BigDecimal widthIn,
                           BigDecimal heightIn) {

        public BoxInput {
            contents = contents == null ? List.of() : List.copyOf(contents);
        }
    }

    /** Units of one plan SKU in a carton. */
    public record BoxItem(String sku, int qty) {
    }

    /**
     * A listing that was approved as Fulfilled by AfroTransact: what the seller said they will send.
     *
     * @param productAtin the product page
     * @param lines       one per variant with units: ATIN, the seller SKU (= the offer's inventory SKU) and quantity
     */
    public record ApprovedListing(String ownerOrg, String productAtin, List<PlanLine> lines) {

        public ApprovedListing {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Buy the inbound label from carrier-gateway (one parcel per box). */
    public record BuyLabel(String carrier, String service, LocalDate shipDate) {
    }

    /**
     * What the FC counted at the dock.
     *
     * @param boxesReceived LPNs scanned at the dock
     */
    public record ReceivePlan(List<ReceiveLine> lines, List<String> boxesReceived) {

        public ReceivePlan {
            lines = lines == null ? List.of() : List.copyOf(lines);
            boxesReceived = boxesReceived == null ? List.of() : List.copyOf(boxesReceived);
        }
    }

    /**
     * Units of one SKU, identified by SKU or by the scanned AT-FN unit label.
     *
     * @param lotNo      default: one lot per receipt
     * @param holdReason receive the good units into quarantine (QC hold)
     * @param cost       what the units cost, for AfroTransact-owned stock (an ASN of AfroTransact Retail); null = no
     *                   cost recorded. Seller (FBA) units carry none: a cost on them is refused.
     */
    public record ReceiveLine(String sku, String unitLabel, String atin, int qtyGood, int qtyDamaged, String lotNo,
                              LocalDate mfgDate, LocalDate expDate, String bin, String holdReason,
                              ReceiveCost cost) {

        /** A line without cost. */
        public ReceiveLine(String sku, String unitLabel, String atin, int qtyGood, int qtyDamaged, String lotNo,
                           LocalDate mfgDate, LocalDate expDate, String bin, String holdReason) {
            this(sku, unitLabel, atin, qtyGood, qtyDamaged, lotNo, mfgDate, expDate, bin, holdReason, null);
        }
    }

    /**
     * The unit cost of received stock in the currency actually paid.
     *
     * @param unitMinor  unit landed cost in minor units of {@code currency}
     * @param fxRateToUsd US dollars per 1 unit of {@code currency}; null = the staff-managed table's latest rate on or
     *                   before the receiving date
     * @param fxRateDate the day the rate is for; null = the receiving date
     * @param components goods, freight, duty and other per unit (add up to {@code unitMinor}); null = all goods
     */
    public record ReceiveCost(long unitMinor, String currency, BigDecimal fxRateToUsd, LocalDate fxRateDate,
                              CostComponents components) {
    }

    /**
     * A retail-service ASN (retail.shipment.stage-changed, S226).
     *
     * @param stage retail timeline stage (DEPARTED, …, RECEIVED)
     */
    public record RetailShipmentNotice(String shipmentId, String reference, String stage, String nodeId,
                                       LocalDate eta, List<NoticeLine> lines) {

        public RetailShipmentNotice {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Units of one SKU on the ASN. */
    public record NoticeLine(String sku, int qty) {
    }
}
