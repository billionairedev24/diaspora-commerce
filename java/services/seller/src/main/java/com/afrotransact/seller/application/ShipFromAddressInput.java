package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.PickupWindow;

/**
 * A ship-from address as entered in Seller Center.
 *
 * @param makeDefault  also make it the organisation's default (the first address always is)
 * @param version      the version the client edited (updates only; null skips the check)
 * @param pickupHours  creates only: the hours AfroTransact can collect here. With them a new location is Fulfilled by
 *                     AfroTransact by default whenever that is possible (approved organisation, street-level located
 *                     address inside the delivery area); without them nothing is enabled, hours are never invented
 */
public record ShipFromAddressInput(String label, String name, String company, String phone, String line1,
                                   String line2, String city, String state, String postalCode, String country,
                                   boolean makeDefault, Long version, List<PickupWindow> pickupHours) {

    public ShipFromAddressInput {
        pickupHours = pickupHours == null ? List.of() : List.copyOf(pickupHours);
    }

    /** An address without pickup hours (nothing is enabled by default). */
    public ShipFromAddressInput(String label, String name, String company, String phone, String line1, String line2,
                                String city, String state, String postalCode, String country, boolean makeDefault,
                                Long version) {
        this(label, name, company, phone, line1, line2, city, state, postalCode, country, makeDefault, version,
                List.of());
    }
}
