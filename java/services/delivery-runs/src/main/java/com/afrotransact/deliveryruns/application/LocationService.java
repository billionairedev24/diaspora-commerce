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

    /** Locations are never deleted (runs and consents refer to them); this deactivates (no reason on record). */
    Location deactivate(UUID id);

    /**
     * Deactivates with the reason that is audited with who and when. Refused (409) while the location has runs that
     * announced, take orders or are being delivered: cancel or finish them first. Already inactive: unchanged.
     */
    Location deactivate(UUID id, String reason);

    /** Switches a deactivated location back on; the optional reason is audited. Already active: unchanged. */
    Location activate(UUID id, String reason);

    /** Runs (newest first, up to 100), subscriber counts and 12 months of subscriber growth. */
    LocationHistory history(UUID id);
}
