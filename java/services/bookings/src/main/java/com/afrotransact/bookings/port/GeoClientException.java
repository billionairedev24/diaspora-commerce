package com.afrotransact.bookings.port;

/** geo-service could not answer (unreachable, 5xx, timeout). */
public class GeoClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GeoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
