package com.afrotransact.retail.port;

import java.util.Map;

/** Inventory refused the request (4xx): the receipt must be corrected; retrying the same request will not help. */
public class InventoryRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public InventoryRejectedException(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
