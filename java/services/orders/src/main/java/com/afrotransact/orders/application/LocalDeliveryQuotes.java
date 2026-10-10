package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.PickupLocations;

/**
 * AfroTransact Delivery from the seller's store at checkout (S255): where an AFROTRANSACT_DELIVERY offer is collected,
 * how far that is from the customer (geo-service, PostGIS) and what it costs (payments delivery pricing).
 */
public interface LocalDeliveryQuotes {

    /** Cart fulfilment value of offers delivered by AfroTransact from the seller's store. */
    String FULFILMENT = "AFROTRANSACT_DELIVERY";

    /**
     * The checked pickup location of a cart item's offer: the seller's own, AfroTransact pickup enabled, geocoded.
     *
     * @throws com.afrotransact.orders.domain.OrdersRuleViolation {@code orders.delivery-pickup-unavailable}
     */
    PickupLocations.PickupLocation pickupFor(CartClient.Item item);

    /**
     * Measures the trip and prices it; the snapshot goes on the shipment.
     *
     * @throws com.afrotransact.orders.domain.OrdersRuleViolation {@code orders.delivery-out-of-range} beyond the
     *         service radius, {@code orders.delivery-unavailable} when not offered,
     *         {@code orders.delivery-address-not-located} when geo can't place the address
     */
    LocalDelivery quote(String sellerOrgId, String sellerName, PickupLocations.PickupLocation from, ShipTo to);
}
