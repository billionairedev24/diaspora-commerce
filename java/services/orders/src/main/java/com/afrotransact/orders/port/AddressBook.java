package com.afrotransact.orders.port;

import java.util.Optional;

import com.afrotransact.orders.domain.ShipTo;

/** identity-service address book ({@code GET /v1/me/addresses} with the customer's token). */
public interface AddressBook {

    Optional<ShipTo> find(String customerBearer, String addressId);
}
