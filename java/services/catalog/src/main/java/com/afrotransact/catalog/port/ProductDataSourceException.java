package com.afrotransact.catalog.port;

/** A product-data provider failed (timeout, 5xx, bad credentials); the lookup moves on to the next provider. */
public class ProductDataSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductDataSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
