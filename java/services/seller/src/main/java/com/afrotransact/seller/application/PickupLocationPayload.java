package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.PickupWindow;
import com.afrotransact.seller.domain.ShipFromAddress;

/**
 * Payload of {@code seller.pickup_location.updated} (S255): an AfroTransact Delivery pickup location was enabled,
 * changed, disabled or deleted. Offers and fulfilment can follow it; checkout re-reads the location anyway.
 *
 * @param deleted the address was deleted (enabled is then false)
 */
public record PickupLocationPayload(String orgId, UUID locationId, String label, boolean enabled, boolean deleted,
                                    Double lat, Double lng, String zoneName, String city, String state,
                                    String postalCode, String country, List<Window> hours, Instant occurredAt) {

    /** One pickup window: day MONDAY…SUNDAY, local HH:mm times. */
    public record Window(String day, String opens, String closes) {
    }

    static PickupLocationPayload of(ShipFromAddress a, boolean deleted, Instant at) {
        boolean enabled = !deleted && a.pickup().enabled();
        return new PickupLocationPayload(a.orgId(), a.id(), a.label(), enabled, deleted,
                a.position() == null ? null : a.position().lat(), a.position() == null ? null : a.position().lng(),
                a.pickup().zoneName(), a.city(), a.state(), a.postalCode(), a.country(),
                a.pickup().hours().stream().map(PickupLocationPayload::window).toList(), at);
    }

    private static Window window(PickupWindow w) {
        return new Window(w.day().name(), w.opens().toString(), w.closes().toString());
    }
}
