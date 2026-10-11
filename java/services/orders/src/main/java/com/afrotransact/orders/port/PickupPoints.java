package com.afrotransact.orders.port;

import java.util.Optional;

/**
 * Customer pickup points (carrier-gateway {@code GET /v1/pickup-locations/{id}}, public): AfroTransact counters and
 * lockers that staff configure for platform-fulfilled packages, and sellers' own pickup locations (their store
 * address or another place) for their packages.
 */
public interface PickupPoints {

    /** The pickup point; empty when carrier-gateway does not know it. */
    Optional<PickupPoint> find(String id);

    /**
     * A pickup point.
     *
     * @param kind     PLATFORM_COUNTER | LOCKER | SELLER
     * @param ownerOrg the seller organisation of a SELLER point (the platform's owner id otherwise)
     */
    record PickupPoint(String id, String kind, String ownerOrg, String name, boolean active) {

        public boolean seller() {
            return "SELLER".equalsIgnoreCase(kind);
        }
    }
}
