package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.ShipTo;

/**
 * What the driver app shows for a stop: address, contact, the customer's preferences and instructions, whether the
 * parcel is perishable (no safe place) and whether the customer's handover code must be collected (the code itself is
 * never shown to drivers).
 */
public record DeliveryBrief(String shipmentId, String orderNumber, String status, ShipTo shipTo,
                            DeliveryPreferences preferences, boolean perishable, boolean handoverRequired,
                            boolean safePlaceAllowed, int units, String sellerName, String nodeId) {
}
