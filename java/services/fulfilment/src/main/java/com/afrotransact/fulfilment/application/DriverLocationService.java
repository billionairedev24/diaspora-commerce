package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.domain.DriverPosition;

/**
 * Drivers' last known positions (S255): every synced driver event carries the device's GPS, and the app can ping
 * {@code POST /v1/driver/location}. Stored as PostGIS geography with its time; an older fix never overwrites a newer
 * one.
 */
public interface DriverLocationService {

    /** {@code POST /v1/driver/location} (delivery:operate). */
    DriverPosition ping(LocationPing ping, DriverCredential credential);

    /** The newest positioned event of a sync batch (events captured before an access revocation only). */
    void fromSync(DriverContext driver, String deviceId, List<DriverEvent> events);
}
