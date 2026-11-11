package com.afrotransact.bookings.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.bookings.domain.CustomerPlace;
import com.afrotransact.bookings.domain.ZoneCity;

/**
 * geo-service as bookings uses it (contracts/openapi/geo.yaml): the ZIP table ({@code GET /v1/zip/{zip}}: city,
 * state, centroid) and the cities around a point ({@code GET /v1/zips/nearby}). Both are public reads.
 */
public interface GeoClient {

    /**
     * The place of a ZIP.
     *
     * @return empty when geo does not know the ZIP
     * @throws GeoClientException when geo-service cannot be reached
     */
    Optional<CustomerPlace> place(String zip);

    /** Cities with a ZIP centroid within {@code miles} of the point, nearest first (at most {@code limit}). */
    List<ZoneCity> citiesNear(double lat, double lon, int miles, int limit);
}
