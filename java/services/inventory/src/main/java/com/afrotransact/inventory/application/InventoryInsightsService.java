package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.domain.AgeBucket;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import com.afrotransact.inventory.port.Page;

/**
 * Read views: the seller's FBA inventory (S067), the staff inventory hub overview and lot recall traces (S066).
 */
public interface InventoryInsightsService {

    /**
     * FBA stock per SKU × fulfilment centre with sellable, reserved, inbound, unfulfillable (quarantined, damaged,
     * expired), ageing since receipt, storage volume and stranded flags, plus totals. Sellers see their own
     * organisation; staff name one.
     */
    FbaInventory fbaInventory(String ownerOrg, String nodeId, String sku, boolean strandedOnly, int page, int size);

    /** Hub overview (inventory:read; sellers: their own stock). */
    InventoryInsightsRepository.Overview overview(String nodeId, String ownerOrg);

    /** Everything about one lot for a recall: the lot, its ledger, related lots and the orders that took units. */
    LotTrace lotTrace(UUID lotId);

    /** A page of FBA stock and the owner's totals. */
    record FbaInventory(String ownerOrg, Page<FbaItem> items, FbaSummary summary) {
    }

    /**
     * One SKU at one FC.
     *
     * @param cubicFeet      volume of the units held (unmeasured SKUs use the default unit volume)
     * @param strandedReason NO_OFFER (no Fulfilled-by-AfroTransact offer) or OFFER_INACTIVE (paused, withdrawn or
     *                       product not live); null when sellable
     */
    record FbaItem(InventoryInsightsRepository.FbaStockRow stock, BigDecimal cubicFeet, boolean measured,
                   boolean stranded, String strandedReason) {
    }

    /** Totals over every FBA position of the owner. */
    record FbaSummary(int skus, long onHand, long reserved, long available, long inbound, long unfulfillable,
                      long quarantined, long strandedUnits, int strandedSkus, BigDecimal cubicFeet,
                      Map<AgeBucket, Long> ageing) {
    }

    /**
     * A lot's recall trace.
     *
     * @param relatedLots the same batch elsewhere: transfer copies and the -DMG lot
     */
    record LotTrace(Lot lot, List<Movement> movements, List<Lot> relatedLots,
                    List<InventoryInsightsRepository.LotConsumer> orders) {
    }
}
