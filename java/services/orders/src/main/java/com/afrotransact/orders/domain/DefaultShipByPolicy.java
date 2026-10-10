package com.afrotransact.orders.domain;

import java.time.LocalDate;

/**
 * Handling time in business days after the order day: AfroTransact-fulfilled shipments (FBA, Retail 1P) and
 * seller-fulfilled shipments have their own handling time; pickup orders must be ready by the same deadline.
 */
public final class DefaultShipByPolicy implements ShipByPolicy {

    private final int afrotransactDays;
    private final int sellerDays;

    public DefaultShipByPolicy(int afrotransactDays, int sellerDays) {
        this.afrotransactDays = Math.max(0, afrotransactDays);
        this.sellerDays = Math.max(0, sellerDays);
    }

    @Override
    public LocalDate shipBy(LocalDate placedOn, FulfilmentType fulfilmentType, DeliveryMethod method) {
        if (placedOn == null) {
            return null;
        }
        return BusinessDays.add(placedOn, fulfilmentType.afrotransactFulfilled() ? afrotransactDays : sellerDays);
    }
}
