package com.afrotransact.catalog.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * GTIN prefill settings (from {@code at.catalog.product-data.*}).
 *
 * @param order    provider names in lookup order
 * @param cacheTtl how long a found GTIN stays cached
 * @param missTtl  how long a GTIN no provider knows stays cached
 */
public record ProductDataSettings(List<String> order, Duration cacheTtl, Duration missTtl) {

    public ProductDataSettings {
        order = List.copyOf(order);
        Objects.requireNonNull(cacheTtl, "cacheTtl");
        Objects.requireNonNull(missTtl, "missTtl");
    }
}
