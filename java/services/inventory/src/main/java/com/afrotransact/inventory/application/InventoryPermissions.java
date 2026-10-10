package com.afrotransact.inventory.application;

/** Permission keys checked by inventory use cases (granted by identity-service roles and client scopes). */
public final class InventoryPermissions {

    /** View positions, lots, reservations and movements (sellers: only their own FBA stock). */
    public static final String READ = "inventory:read";
    /** Cycle counts, quarantine, release, write-off, transfers, returns, nodes. */
    public static final String ADJUST = "inventory:adjust";
    /** Goods receipts and inbound notices (retail service, FC receivers). */
    public static final String RECEIVE = "inventory:receive";
    /** Reserve / confirm / commit / release; only SERVICE principals (orders service) hold it. */
    public static final String RESERVE = "inventory:reserve";

    private InventoryPermissions() {
    }
}
