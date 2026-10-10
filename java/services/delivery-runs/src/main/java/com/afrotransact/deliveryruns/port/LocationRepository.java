package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Location;

/** Locations (towns / areas). */
public interface LocationRepository {

    /** @throws DuplicateEntryException when the slug is taken */
    Location insert(Location location);

    /** Optimistic: fails with a conflict when {@code location.version()} is stale. */
    Location update(Location location);

    Optional<Location> findById(UUID id);

    Optional<Location> findBySlug(String slug);

    /** All locations, or only active / inactive ones; by name. */
    List<Location> findAll(Boolean active);
}
