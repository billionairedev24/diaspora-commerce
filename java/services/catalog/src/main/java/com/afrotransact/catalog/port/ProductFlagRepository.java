package com.afrotransact.catalog.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductFlag;

/** Reports and flags raised against live listings. */
public interface ProductFlagRepository {

    /**
     * Stores the flag. A shopper reports a listing once: when {@code flag} is a customer report and the same
     * reporter already reported the product, nothing is stored and the earlier report is returned instead.
     *
     * @return the stored flag and whether it is new
     */
    Stored insert(ProductFlag flag);

    Optional<ProductFlag> findById(UUID id);

    /** Result of {@link #insert}. */
    record Stored(ProductFlag flag, boolean created) {
    }
}
