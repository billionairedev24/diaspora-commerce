package com.afrotransact.offers.port;

import java.util.Optional;

import com.afrotransact.offers.domain.OfferPolicy;

/** The staff-edited copy of the {@link OfferPolicy} (one row). Absent until staff save it for the first time. */
public interface OfferSettingsRepository {

    Optional<OfferPolicy> find();

    /**
     * Stores the policy when {@code policy.version()} is the stored version (0 when nothing is stored yet) and returns it
     * with version + 1.
     *
     * @throws com.afrotransact.platform.web.error.ConflictException when somebody saved in between
     */
    OfferPolicy save(OfferPolicy policy);
}
