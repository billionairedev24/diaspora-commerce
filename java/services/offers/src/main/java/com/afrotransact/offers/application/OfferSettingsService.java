package com.afrotransact.offers.application;

import com.afrotransact.offers.domain.OfferPolicy;

/** The thresholds of matched offers and the tax release: configured defaults, edited by staff. */
public interface OfferSettingsService {

    /** The policy in force (the staff copy, else the configured defaults). No permission check: internal callers. */
    OfferPolicy current();

    /** The policy for the staff screen (staff with offers:write). */
    OfferPolicy view();

    /**
     * Saves the staff copy (staff with offers:write). 422 for an invalid value; 409 {@code offers.concurrent-update} when
     * {@code next.version()} is not the stored version.
     */
    OfferPolicy update(OfferPolicy next);
}
