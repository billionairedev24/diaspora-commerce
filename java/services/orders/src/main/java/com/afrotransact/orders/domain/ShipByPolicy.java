package com.afrotransact.orders.domain;

import java.time.LocalDate;

/** The date by which a shipment must be handed to the carrier / courier (seller health lateness, fulfilment). */
public interface ShipByPolicy {

    LocalDate shipBy(LocalDate placedOn, FulfilmentType fulfilmentType, DeliveryMethod method);
}
