package com.afrotransact.deliveryruns.port;

/** geo-service could not be reached or failed (HTTP 502 to callers). */
public class GeoUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GeoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
