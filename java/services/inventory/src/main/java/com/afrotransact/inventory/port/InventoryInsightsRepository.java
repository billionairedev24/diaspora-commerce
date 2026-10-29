package com.afrotransact.inventory.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.domain.AgeBucket;
import com.afrotransact.inventory.domain.ExpiryBucket;

/** Read models over positions, lots, inbound shipments and reservations (seller FBA view S067, hub S066). */
public interface InventoryInsightsRepository {

    /** FBA stock of one owner per SKU × fulfilment centre, with lot ageing, volume and offer links. */
    Page<FbaStockRow> fbaStock(FbaQuery query);

    /** Totals, value, expiry buckets, per-node figures and alerts for the staff inventory hub. */
    Overview overview(String nodeId, String ownerOrg, int alertLimit);

    /** Orders that took units of a lot (reservation allocations), newest first: who to call in a recall. */
    List<LotConsumer> consumersOfLot(UUID lotId);

    /** Filter of {@link #fbaStock}; {@code strandedOnly} keeps stock without a live FBA offer. */
    record FbaQuery(String ownerOrg, String nodeId, String sku, boolean strandedOnly, int page, int size) {
    }

    /**
     * One SKU at one FC.
     *
     * @param quarantined     units in QUARANTINE lots (QC hold, short-dated)
     * @param damagedLots     units in DAMAGED lots (damaged on receipt or return)
     * @param expired         units in EXPIRED lots
     * @param ageing          units on hand or held per age since receipt
     * @param unitCubicFeet   unit volume, null when the SKU has no dimensions
     * @param openOffers      open Fulfilled-by-AfroTransact offers on this stock
     * @param liveOffers      of which ACTIVE with a live product
     */
    record FbaStockRow(String sku, String nodeId, String ownerOrg, String atin, String unitLabel, int onHand,
                       int reserved, int available, int inbound, int unfulfillable, int quarantined, int damagedLots,
                       int expired, Map<AgeBucket, Integer> ageing, Instant oldestReceivedAt,
                       BigDecimal unitCubicFeet, int openOffers, int liveOffers) {

        public FbaStockRow {
            ageing = Map.copyOf(ageing);
        }

        /** Sellable stock nobody can buy: no live Fulfilled-by-AfroTransact offer. */
        public boolean stranded() {
            return onHand > 0 && liveOffers == 0;
        }
    }

    /** The hub's overview. */
    record Overview(List<StatusValue> byStatus, List<ExpiryCount> expiry, List<NodeFigures> nodes,
                    InboundFigures inbound, ReservationFigures reservations, List<Alert> alerts) {

        public Overview {
            byStatus = List.copyOf(byStatus);
            expiry = List.copyOf(expiry);
            nodes = List.copyOf(nodes);
            alerts = List.copyOf(alerts);
        }
    }

    /**
     * Units and landed value (lot unit cost × units) per lot status and currency the lots were paid in: the value in
     * that currency and in US dollars (what totals and reports use).
     *
     * @param valueMinor    value in minor units of {@code currency}
     * @param valueUsdMinor value in US cents, at the rates fixed when the lots were received
     */
    record StatusValue(String status, String currency, long units, long lots, long valueMinor, long valueUsdMinor) {
    }

    /** What a node holds that was paid in one currency: the amount in it and in US dollars. */
    record CurrencyValue(String currency, long units, long valueMinor, long valueUsdMinor) {
    }

    /** Units and lots per expiry bucket (node-local days to expiry). */
    record ExpiryCount(ExpiryBucket bucket, long units, long lots) {
    }

    /**
     * Per node: lots, units, sellable and blocked units, landed value in US dollars (with the amounts in the currencies
     * paid alongside), reserved and inbound.
     *
     * @param valueMinor value of the node's stock in US cents; {@code currency} is therefore always USD
     * @param byCurrency the same stock split by the currency it was paid in
     */
    record NodeFigures(String nodeId, String name, String kind, long lots, long units, long sellableUnits,
                       long blockedUnits, long valueMinor, String currency, long reserved, long inbound,
                       List<CurrencyValue> byCurrency) {

        public NodeFigures {
            byCurrency = List.copyOf(byCurrency);
        }
    }

    /** Open inbound shipments and their expected / received units. */
    record InboundFigures(long openShipments, long expectedUnits, long receivedUnits, long withDiscrepancies) {
    }

    /** Checkout holds still HELD. */
    record ReservationFigures(long held, long heldUnits) {
    }

    /**
     * Something the inventory team should act on.
     *
     * @param type LOT_EXPIRED, LOT_EXPIRING, LOW_STOCK or INBOUND_DISCREPANCY
     */
    record Alert(String type, String severity, String sku, String nodeId, UUID lotId, String lotNo, UUID shipmentId,
                 Long qty, LocalDate date, String message) {
    }

    /** Units of a lot allocated to an order (recall trace). */
    record LotConsumer(String orderId, UUID reservationId, int lineNo, String group, String lineStatus,
                       String reservationStatus, int qty, Instant updatedAt) {
    }
}
