package com.afrotransact.catalog.domain.imports;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;

/**
 * One validated offer row (S393): the offer terms the seller gave and the page they belong to.
 *
 * @param atin     the variant the offer goes on (the page's own content is never read from the file)
 * @param quantity null for items Fulfilled by AfroTransact (their stock is inventory's)
 * @param held     the page or the seller is not cleared yet: the row is stored as HELD, not sent as an offer
 * @param heldNote why, in plain words
 */
public record OfferRow(int rowNumber, String sku, String atin, String parentAtin, ItemCondition condition,
                       long priceMinor, Long listPriceMinor, Long shippingMinor, FulfilmentMode fulfilment,
                       Integer quantity, Integer handlingDays, String shipCity, String shipRegion, String shipCountry,
                       String shipZip, String conditionNote, Box box, boolean held, String heldNote) {

    /** The box of a Fulfilled by AfroTransact offer, in inches and pounds. */
    public record Box(double lengthIn, double widthIn, double heightIn, double weightLb) {
    }
}
