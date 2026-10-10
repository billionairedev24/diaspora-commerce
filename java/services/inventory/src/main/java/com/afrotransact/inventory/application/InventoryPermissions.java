package com.afrotransact.inventory.application;

/** Permission keys checked by inventory use cases (granted by identity-service roles and client scopes). */
public final class InventoryPermissions {

    /** View positions, lots, reservations and movements (sellers: only their own FBA stock). */
    public static final String READ = "inventory:read";
    /** Cycle counts, quarantine, release, write-off, transfers, returns, nodes. */
    public static final String ADJUST = "inventory:adjust";
    /** Goods receipts and inbound notices (retail service, FC receivers). */
    public static final String RECEIVE = "inventory:receive";
    /** Post inspected customer returns (RETURN_RESTOCK / RETURN_DAMAGED); SERVICE principals (tax-returns). */
    public static final String RETURNS = "inventory:returns";
    /** Reserve / confirm / commit / release; only SERVICE principals (orders service) hold it. */
    public static final String RESERVE = "inventory:reserve";
    /** Read daily FBA storage volume of every owner; SERVICE principals (seller-service metered billing, S206). */
    public static final String STORAGE_READ = "inventory:storage.read";

    private InventoryPermissions() {
    }
}
