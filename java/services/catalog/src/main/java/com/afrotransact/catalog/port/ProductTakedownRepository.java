package com.afrotransact.catalog.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductTakedown;

/** Trust and Safety takedowns of live listings (at most one active per product). */
public interface ProductTakedownRepository {

    ProductTakedown insert(ProductTakedown takedown);

    /** Optimistic update on {@code version}. */
    ProductTakedown update(ProductTakedown takedown);

    Optional<ProductTakedown> findActive(UUID productId);
}
