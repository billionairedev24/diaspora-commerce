package com.afrotransact.catalog.application;

/** Permission keys checked by catalog use cases (granted by identity-service roles). */
public final class CatalogPermissions {

    public static final String WRITE = "catalog:write";
    public static final String MODERATE = "catalog:moderate";
    public static final String TAXONOMY = "catalog:taxonomy";

    private CatalogPermissions() {
    }
}
