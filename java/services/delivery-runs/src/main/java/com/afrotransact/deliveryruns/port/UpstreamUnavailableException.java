package com.afrotransact.deliveryruns.port;

/** media-service, orders-service or catalog-service could not be reached or failed (HTTP 502 to callers). */
public class UpstreamUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String service;

    public UpstreamUnavailableException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
    }

    /** {@code media}, {@code orders} or {@code catalog}. */
    public String service() {
        return service;
    }
}
