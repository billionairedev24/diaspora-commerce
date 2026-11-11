package com.afrotransact.bookings.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.ServiceOffering;

/** Persistence port for providers' services and prices. */
public interface OfferingRepository {

    ServiceOffering insert(ServiceOffering offering);

    ServiceOffering update(ServiceOffering offering);

    Optional<ServiceOffering> find(UUID id);

    List<ServiceOffering> byOrg(String orgId, boolean activeOnly);

    /** Active, visible services whose name, description, category or provider name match {@code text} (null = any). */
    List<ServiceOffering> search(String text, int limit);

    /**
     * The same, restricted to providers with a service area that could possibly serve a customer at the point:
     * the base within max(radius, travelMiles, nearestMiles) miles, or any named extra area (decided by the caller).
     */
    List<ServiceOffering> searchNear(String text, double lat, double lon, int travelMiles, int nearestMiles,
                                     int limit);

    /** Suggestions for the search box: service names and provider names containing {@code text}. */
    List<String> suggestions(String text, int limit);
}
