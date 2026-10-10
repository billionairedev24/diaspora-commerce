package com.afrotransact.retail.port;

/** catalog-service could not be reached or failed; the ATIN cannot be verified right now. */
public class CatalogUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
