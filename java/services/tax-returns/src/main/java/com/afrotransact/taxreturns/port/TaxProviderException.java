package com.afrotransact.taxreturns.port;

/** A provider could not answer a quote (unreachable, error, no usable code, not configured). */
public class TaxProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TaxProviderException(String message) {
        super(message);
    }

    public TaxProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
