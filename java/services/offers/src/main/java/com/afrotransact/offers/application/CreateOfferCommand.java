package com.afrotransact.offers.application;

import java.util.Map;
import java.util.Set;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.ShipFrom;

/**
 * New offer on an existing catalog variant, identified by ATIN or GTIN (exactly one). An offer carries offer terms only:
 * everything about the page (title, images, attributes, brand, category) belongs to the page and is refused.
 *
 * @param sellerOrgId     only staff / service callers may set it; sellers always offer for their own organisation
 * @param inventorySku    defaults to the seller SKU
 * @param quantity        seller-declared stock, required for seller-fulfilled offers
 * @param conditionNote   what is different about a used or renewed item; required for any condition but new
 * @param packaging       the box the seller ships it in; only for Fulfilled by AfroTransact
 * @param sellerBehaviour the seller's own answers about how the item is sold (the tax behaviour facts), for the tax decision
 * @param unknownFields   names of fields the request carried that an offer does not have: page content is refused (422)
 */
public record CreateOfferCommand(String atin, String gtin, String sellerOrgId, String sellerSku, String inventorySku,
                                 OfferCondition condition, long priceMinor, Long listPriceMinor, String currency,
                                 long shippingMinor, Fulfilment fulfilment, Integer quantity, Integer handlingDays,
                                 ShipFrom shipFrom, String conditionNote, OfferPackage packaging,
                                 Map<String, Object> sellerBehaviour, Set<String> unknownFields) {

    public CreateOfferCommand {
        sellerBehaviour = sellerBehaviour == null ? Map.of() : sellerBehaviour;
        unknownFields = unknownFields == null ? Set.of() : Set.copyOf(unknownFields);
    }

    /** An offer with no condition note, package, behaviour answers or stray fields. */
    public CreateOfferCommand(String atin, String gtin, String sellerOrgId, String sellerSku, String inventorySku,
                              OfferCondition condition, long priceMinor, Long listPriceMinor, String currency,
                              long shippingMinor, Fulfilment fulfilment, Integer quantity, Integer handlingDays,
                              ShipFrom shipFrom) {
        this(atin, gtin, sellerOrgId, sellerSku, inventorySku, condition, priceMinor, listPriceMinor, currency,
                shippingMinor, fulfilment, quantity, handlingDays, shipFrom, null, null, Map.of(), Set.of());
    }
}
