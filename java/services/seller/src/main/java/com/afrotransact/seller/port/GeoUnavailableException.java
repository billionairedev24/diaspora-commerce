package com.afrotransact.seller.port;

/** geo-service could not be reached or answered with a server error. */
public class GeoUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GeoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
