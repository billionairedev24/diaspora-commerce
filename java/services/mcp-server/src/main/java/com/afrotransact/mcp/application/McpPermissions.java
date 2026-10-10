package com.afrotransact.mcp.application;

/** Platform permissions checked by individual tools, on top of the toolset scope (mcp:shopper / seller / staff). */
public final class McpPermissions {

    public static final String CATALOG_WRITE = "catalog:write";
    public static final String CATALOG_MODERATE = "catalog:moderate";
    public static final String ORDERS_READ = "orders:read";
    public static final String ORDERS_UPDATE_STATUS = "orders:update-status";
    public static final String INVENTORY_READ = "inventory:read";
    public static final String APPLICATIONS_REVIEW = "applications:review";
    public static final String LEDGER_READ = "ledger:read";

    private McpPermissions() {
    }
}
