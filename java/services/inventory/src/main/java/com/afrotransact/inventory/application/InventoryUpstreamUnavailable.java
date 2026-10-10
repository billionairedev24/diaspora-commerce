package com.afrotransact.inventory.application;

/** A service inventory depends on (catalog labels, carrier-gateway) cannot be reached; retry later (HTTP 503). */
public class InventoryUpstreamUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public InventoryUpstreamUnavailable(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
