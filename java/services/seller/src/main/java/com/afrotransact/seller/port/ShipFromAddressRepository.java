package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.ShipFromAddress;

/** Ship-from addresses per organisation. Updates are optimistic on {@code version}. */
public interface ShipFromAddressRepository {

    /** The organisation's addresses, default first, then oldest first. */
    List<ShipFromAddress> list(String orgId);

    Optional<ShipFromAddress> find(String orgId, UUID id);

    void insert(ShipFromAddress address);

    /** Returns false when the stored version differs from {@code address.version()} (someone changed it). */
    boolean update(ShipFromAddress address);

    /** Makes {@code id} the only default of the organisation. */
    void makeDefault(String orgId, UUID id);

    void delete(String orgId, UUID id);
}
