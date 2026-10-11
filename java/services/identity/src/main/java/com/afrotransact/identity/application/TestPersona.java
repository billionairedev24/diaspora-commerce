package com.afrotransact.identity.application;

import java.util.List;

/**
 * A ready-made local test account (S276).
 *
 * @param email   under {@code afrotransact.test}
 * @param phone   E.164; staff need one (their two-step code is a text message, delivered to Mailpit locally)
 * @param persona who this is, for the "Test users" tables
 * @param app     where the persona signs in: storefront, Seller Center, staff console or the driver app
 * @param roles   account and platform roles
 * @param orgId   organisation the persona works for (created by {@code make seed}; linked once identity mirrors it)
 * @param orgRole role in that organisation
 * @param address saved delivery address (customers), or null
 */
public record TestPersona(String email, String firstName, String lastName, String phone, String persona, String app,
                          List<String> roles, String orgId, String orgRole, Address address) {

    public TestPersona {
        roles = List.copyOf(roles);
    }

    /** A verified, default delivery address in the persona's address book. */
    public record Address(String label, String line1, String city, String state, String postalCode, double latitude,
                          double longitude, String instructions) {
    }
}
