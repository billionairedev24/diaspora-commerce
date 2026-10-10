package com.afrotransact.mcp.port;

/** geo-service ZIP lookup. */
public interface GeoApi {
    Downstream zip(String zip, String bearerToken);
}
