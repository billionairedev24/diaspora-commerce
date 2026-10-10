package com.afrotransact.seller.port;

import java.util.Optional;

import com.afrotransact.seller.domain.GeoPosition;
import com.afrotransact.seller.domain.ShipFromAddress;

/** geo-service {@code POST /v1/geocode}: the position of a ship-from address (provider chain, ZIP fallback). */
public interface Geocoder {

    /**
     * @return empty when geo knows no such address
     * @throws GeoUnavailableException when geo-service cannot be reached
     */
    Optional<GeoPosition> geocode(ShipFromAddress address);
}
