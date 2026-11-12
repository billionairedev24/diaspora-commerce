package com.afrotransact.catalog.port;

import java.util.Optional;

import com.afrotransact.catalog.domain.CatalogPolicy;

/** The staff-edited copy of the {@link CatalogPolicy} (one row). Absent until staff save it for the first time. */
public interface CatalogSettingsRepository {

    Optional<CatalogPolicy> find();

    /**
     * Stores the policy when {@code policy.version()} is the stored version (0 when nothing is stored yet) and returns it
     * with version + 1.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when somebody saved in between
     */
    CatalogPolicy save(CatalogPolicy policy);
}
