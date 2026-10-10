package com.afrotransact.retail.port;

/** media-service (or the object store behind it) could not take or serve a file; retryable. */
public class MediaUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MediaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
