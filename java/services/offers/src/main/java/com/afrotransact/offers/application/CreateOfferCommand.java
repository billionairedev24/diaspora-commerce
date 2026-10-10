package com.afrotransact.offers.application;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.ShipFrom;

/**
 * New offer on an existing catalog variant, identified by ATIN or GTIN (exactly one).
 *
 * @param sellerOrgId  only staff / service callers may set it; sellers always offer for their own organisation
 * @param inventorySku defaults to the seller SKU
 * @param quantity     seller-declared stock, required for seller-fulfilled offers
 */
public record CreateOfferCommand(String atin, String gtin, String sellerOrgId, String sellerSku, String inventorySku,
                                 OfferCondition condition, long priceMinor, Long listPriceMinor, String currency,
                                 long shippingMinor, Fulfilment fulfilment, Integer quantity, Integer handlingDays,
                                 ShipFrom shipFrom) {
}
