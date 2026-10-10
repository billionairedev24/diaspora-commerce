package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.ShipFromAddress;

/**
 * Ship-from addresses of a seller organisation (label form, rates). Every member can read them; the owner, admins
 * and fulfilment members (or staff with {@code seller:ship-from}) change them.
 */
public interface ShipFromAddressService {

    /** Staff permission to read and change any organisation's ship-from addresses. */
    String STAFF_PERMISSION = "seller:ship-from";

    List<ShipFromAddress> list(String orgId);

    ShipFromAddress create(String orgId, ShipFromAddressInput input);

    ShipFromAddress update(String orgId, UUID id, ShipFromAddressInput input);

    /** Makes the address the default; returns the whole list (default first). */
    List<ShipFromAddress> makeDefault(String orgId, UUID id);

    /** Deletes the address; the oldest remaining one becomes the default when the default goes. */
    List<ShipFromAddress> delete(String orgId, UUID id);
}
