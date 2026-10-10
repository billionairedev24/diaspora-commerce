package com.afrotransact.catalog.port;

import java.util.Optional;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.prefill.ProductData;

/** An external product-data provider looked up by GTIN (GS1 Verified by GS1, Open Food Facts …). */
public interface ProductDataSource {

    /** Provider name, also used to order providers ({@code at.catalog.product-data.order}). */
    String name();

    /** False when switched off or not configured (e.g. GS1 without an API key). */
    boolean enabled();

    /**
     * Looks the GTIN up.
     *
     * @return empty when the provider does not know the GTIN
     * @throws ProductDataSourceException when the provider is unreachable, slow or answers with an error
     */
    Optional<ProductData> lookup(Gtin gtin);
}
