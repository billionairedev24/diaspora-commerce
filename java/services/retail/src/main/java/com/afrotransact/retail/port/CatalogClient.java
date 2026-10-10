package com.afrotransact.retail.port;

import java.util.Optional;

/** Reads catalog-service ({@code GET /v1/public/variants/{atin}}) to validate ATIN links. */
public interface CatalogClient {

    /** The live variant with this ATIN, or empty when catalog does not know it (404). */
    Optional<CatalogVariant> liveVariant(String atin);
}
