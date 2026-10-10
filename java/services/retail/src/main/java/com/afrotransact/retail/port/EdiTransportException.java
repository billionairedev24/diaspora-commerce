package com.afrotransact.retail.port;

/** An X12 interchange was not delivered. */
public class EdiTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public EdiTransportException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** True for network / partner outages; false for configuration problems that a retry cannot fix. */
    public boolean retryable() {
        return retryable;
    }
}
