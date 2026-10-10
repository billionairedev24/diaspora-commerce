package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DriverPosition;
import com.afrotransact.fulfilment.domain.GeoPoint;

/** Drivers' last known positions (PostGIS geography) and the nearest-available-driver search. */
public interface DriverPositionRepository {

    /** Stores the position unless a newer one is already known. @return true when stored */
    boolean upsertIfNewer(DriverPosition position);

    Optional<DriverPosition> find(String driverId);

    /**
     * Nearest drivers to {@code pickup} by KNN ({@code <->}) whose position is at least {@code freshAfter}, whose access
     * is not revoked, and who drive an ASSIGNED or IN_PROGRESS route on {@code day} in {@code zone} that is not
     * blocked, passed its vehicle inspection and carries fewer than {@code maxPackages} active packages. One row per
     * driver (their route with the most room), nearest first.
     */
    List<Candidate> nearestAvailable(GeoPoint pickup, String zone, LocalDate day, Instant freshAfter,
                                     int maxPackages, int limit);

    /** A candidate driver with the route the pickup would join. */
    record Candidate(String driverId, String routeId, String routeName, String zone, String routeStatus,
                     double metres, Instant positionAt, int activePackages, GeoPoint position) {
    }
}
