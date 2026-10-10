package com.afrotransact.seller.application;

/**
 * A ship-from address as entered in Seller Center.
 *
 * @param makeDefault also make it the organisation's default (the first address always is)
 * @param version     the version the client edited (updates only; null skips the check)
 */
public record ShipFromAddressInput(String label, String name, String company, String phone, String line1,
                                   String line2, String city, String state, String postalCode, String country,
                                   boolean makeDefault, Long version) {
}
