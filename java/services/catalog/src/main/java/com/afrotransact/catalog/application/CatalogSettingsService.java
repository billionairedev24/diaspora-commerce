package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.CatalogPolicy;

/** The thresholds of matching, exemptions and the review queue: configured defaults, edited by staff. */
public interface CatalogSettingsService {

    /** The policy in force (the staff copy, else the configured defaults). No permission check: internal callers. */
    CatalogPolicy current();

    /** The policy for the staff screen (catalog:moderate). */
    CatalogPolicy view();

    /**
     * Saves the staff copy (catalog:moderate). 422 for an invalid value; 409 {@code catalog.concurrent-update} when
     * {@code next.version()} is not the stored version.
     */
    CatalogPolicy update(CatalogPolicy next);
}
