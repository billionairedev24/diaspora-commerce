package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Location;

/** Staff: towns / areas served by delivery runs. */
public interface LocationService {

    Location create(LocationCommand command);

    Location update(UUID id, LocationCommand command);

    Location get(UUID id);

    List<Location> list(Boolean active);

    /** Locations are never deleted (runs and consents refer to them); this deactivates. */
    Location deactivate(UUID id);

    /** Runs (newest first, up to 100), subscriber counts and 12 months of subscriber growth. */
    LocationHistory history(UUID id);
}
