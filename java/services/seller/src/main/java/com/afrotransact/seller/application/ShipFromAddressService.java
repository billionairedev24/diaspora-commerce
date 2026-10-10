package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.ShipFromAddress;

/**
 * Ship-from addresses of a seller organisation (label form, rates, AfroTransact Delivery pickup). Every member can
 * read them; the owner, admins and fulfilment members (or staff with {@code seller:ship-from}) change them. Addresses
 * are geocoded with geo-service when saved.
 */
public interface ShipFromAddressService {

    /** Staff permission to read and change any organisation's ship-from addresses. */
    String STAFF_PERMISSION = "seller:ship-from";

    /**
     * Service clients (offers-service, orders-service, fulfilment-service) reading one location by id, e.g. to check
     * that AfroTransact pickup is enabled and get its position (S255).
     */
    String SERVICE_READ = "sellers:ship-from.read";

    /**
     * Turns AfroTransact Delivery pickup on or off (S255). Enabling needs an approved organisation
     * ({@code 409 seller.org-not-approved}), pickup hours, a street-level geocoded position and a location inside an
     * active DELIVERY zone or within the configured radius of one ({@code 422 seller.pickup-outside-delivery-area}).
     */
    ShipFromAddress setPickup(String orgId, UUID id, PickupInput input);

    /** One location by id: services with {@link #SERVICE_READ}, staff with {@link #STAFF_PERMISSION}, members. */
    ShipFromAddress location(UUID id);

    List<ShipFromAddress> list(String orgId);

    ShipFromAddress create(String orgId, ShipFromAddressInput input);

    ShipFromAddress update(String orgId, UUID id, ShipFromAddressInput input);

    /** Makes the address the default; returns the whole list (default first). */
    List<ShipFromAddress> makeDefault(String orgId, UUID id);

    /** Deletes the address; the oldest remaining one becomes the default when the default goes. */
    List<ShipFromAddress> delete(String orgId, UUID id);
}
