package com.afrotransact.retail.port;

/** Inventory could not be reached or failed (5xx, timeout): safe to retry with the same Idempotency-Key. */
public class InventoryUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
