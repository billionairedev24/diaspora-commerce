package com.afrotransact.inventory.domain;

/** The aggregate is in a state that does not allow the change (HTTP 409), for example releasing a shipped order. */
public class InventoryStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public InventoryStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
