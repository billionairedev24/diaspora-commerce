package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.AddressBookPolicy.AddressDraft;
import com.afrotransact.identity.domain.PostalAddress;

/** The signed-in customer's address book (checkout, account pages, mobile app). */
public interface AddressBookService {

    List<PostalAddress> list();

    /** Adds an address; the first address becomes the default automatically. */
    PostalAddress add(AddressDraft draft, Coordinates coordinates, boolean verified, boolean makeDefault);

    PostalAddress update(UUID addressId, AddressDraft draft, Coordinates coordinates, boolean verified);

    /** Removes an address; if it was the default, the most recently updated remaining address becomes default. */
    void remove(UUID addressId);

    PostalAddress makeDefault(UUID addressId);

    /** Optional coordinates from the geo service's resolved suggestion. */
    record Coordinates(Double latitude, Double longitude) {
    }
}
