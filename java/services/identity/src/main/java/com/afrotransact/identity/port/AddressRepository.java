package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.PostalAddress;

/** Persistence port for customer address books. */
public interface AddressRepository {

    List<PostalAddress> findByUser(UUID userId);

    Optional<PostalAddress> findById(UUID userId, UUID addressId);

    void insert(PostalAddress address);

    void update(PostalAddress address);

    void delete(UUID userId, UUID addressId);

    /** Makes exactly one address the default for the user (in the caller's transaction). */
    void setDefault(UUID userId, UUID addressId);

    int count(UUID userId);
}
