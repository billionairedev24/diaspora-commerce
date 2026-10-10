package com.afrotransact.deliveryruns.port;

/** Registers a location's polygon as a geo-service zone ({@code POST /v1/zones}, scope {@code geo:zones:write}). */
public interface GeoZoneRegistry {

    /**
     * @param name    zone name (the location's name)
     * @param ownerId zone owner, {@code delivery-runs:<location slug>}
     * @param geoJson GeoJSON Polygon or MultiPolygon
     * @return the geo zone id
     */
    String registerPolygon(String name, String ownerId, String geoJson);
}
