package com.afrotransact.mcp.port;

/** bookings-service service search. */
public interface BookingsApi {
    Downstream findServices(String query, String zip, String bearerToken);
}
