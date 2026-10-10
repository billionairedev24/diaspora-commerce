package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.prefill.ProductData;

/** Cached provider answers per GTIN (hits and misses), shared by every catalog pod. */
public interface ProductDataCache {

    /** The cached provider results (empty list = cached miss), or empty when nothing fresh is cached. */
    Optional<List<ProductData>> find(Gtin gtin, Instant now);

    void store(Gtin gtin, List<ProductData> results, Instant fetchedAt, Instant expiresAt);
}
