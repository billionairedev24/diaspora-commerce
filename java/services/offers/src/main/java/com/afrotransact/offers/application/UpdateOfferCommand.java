package com.afrotransact.offers.application;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.ShipFrom;

/** Partial change of an offer's terms: null leaves a field unchanged; {@code listPriceMinor = 0} clears it. */
public record UpdateOfferCommand(OfferCondition condition, Long priceMinor, Long listPriceMinor, Long shippingMinor,
                                 Fulfilment fulfilment, Integer quantity, Integer handlingDays, ShipFrom shipFrom,
                                 String inventorySku) {
}
