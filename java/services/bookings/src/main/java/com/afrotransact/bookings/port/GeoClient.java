package com.afrotransact.bookings.port;

import java.util.Optional;
import java.util.Set;

/**
 * geo-service as bookings uses it (contracts/openapi/geo.yaml): ZIP lookup ({@code GET /v1/zip/{zip}}) and the
 * provider service areas covering a point ({@code GET /v1/zones/covering?lat&lon&type=SERVICE_AREA}). Both are
 * public reads.
 */
public interface GeoClient {

    /**
     * Organisations whose active service area covers the ZIP's location.
     *
     * @return empty when geo does not know the ZIP
     * @throws GeoClientException when geo-service cannot be reached
     */
    Optional<Set<String>> serviceAreaOwners(String zip);
}
