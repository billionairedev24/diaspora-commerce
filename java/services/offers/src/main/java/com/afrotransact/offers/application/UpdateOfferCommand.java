package com.afrotransact.offers.application;

import java.util.Set;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.ShipFrom;

/**
 * Partial change of an offer's terms: null leaves a field unchanged; {@code listPriceMinor = 0} clears it.
 *
 * @param conditionNote required when the condition changes to anything but new (and none is stored yet)
 * @param packaging     only for Fulfilled by AfroTransact
 * @param unknownFields names of fields the request carried that an offer does not have: page content is refused (422)
 */
public record UpdateOfferCommand(OfferCondition condition, Long priceMinor, Long listPriceMinor, Long shippingMinor,
                                 Fulfilment fulfilment, Integer quantity, Integer handlingDays, ShipFrom shipFrom,
                                 String inventorySku, String conditionNote, OfferPackage packaging,
                                 Set<String> unknownFields) {

    public UpdateOfferCommand {
        unknownFields = unknownFields == null ? Set.of() : Set.copyOf(unknownFields);
    }

    /** A change of the terms only. */
    public UpdateOfferCommand(OfferCondition condition, Long priceMinor, Long listPriceMinor, Long shippingMinor,
                              Fulfilment fulfilment, Integer quantity, Integer handlingDays, ShipFrom shipFrom,
                              String inventorySku) {
        this(condition, priceMinor, listPriceMinor, shippingMinor, fulfilment, quantity, handlingDays, shipFrom,
                inventorySku, null, null, Set.of());
    }
}
