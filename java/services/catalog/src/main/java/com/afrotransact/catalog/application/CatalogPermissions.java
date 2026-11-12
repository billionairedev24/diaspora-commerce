package com.afrotransact.catalog.application;

/** Permission keys checked by catalog use cases (granted by identity-service roles). */
public final class CatalogPermissions {

    public static final String WRITE = "catalog:write";
    /** Read-only catalog access (service clients such as offers-service): GTIN lookups. */
    public static final String READ = "catalog:read";
    public static final String MODERATE = "catalog:moderate";
    public static final String TAXONOMY = "catalog:taxonomy";
    /** The staff tax permission (tax-returns, gateway-bff): reads the live-without-tax-decision audit. */
    public static final String TAX_READ = "tax:read";

    private CatalogPermissions() {
    }
}
