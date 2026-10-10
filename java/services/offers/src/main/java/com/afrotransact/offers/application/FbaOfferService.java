package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/**
 * S257: switches a seller's listing to Fulfilled by AfroTransact once their FBA stock is received at a fulfilment
 * centre (inventory.inbound.received, inbox de-duplicated).
 */
public interface FbaOfferService {

    /**
     * For every received line with good units: when the seller has no open Fulfilled-by-AfroTransact offer on the
     * variant, one is created from their seller-fulfilled offer (same price, list price, currency, condition and
     * seller SKU, no shipping charge, inventory SKU = the received SKU), so the product is sold both from the seller's
     * store and from the fulfilment centre. Lines without any offer of the seller stay stranded in inventory.
     *
     * @return the offers created
     */
    List<UUID> stockReceived(UUID eventId, ReceivedStock stock);

    /**
     * FBA stock received against an inbound shipment.
     *
     * @param source FBA (seller inbound plan) or RETAIL_ASN (AfroTransact Retail purchase order; ignored here)
     */
    record ReceivedStock(String shipmentId, String source, String ownerOrg, String nodeId, List<Line> lines) {

        public ReceivedStock {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** One received SKU (inventory SKU) of a variant ATIN. */
        public record Line(String sku, String atin, int qtyGood) {
        }
    }
}
