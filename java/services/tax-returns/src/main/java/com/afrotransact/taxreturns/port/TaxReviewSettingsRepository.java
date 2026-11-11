package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.ReviewSettingsChange;

/** The tax review settings staff change at runtime (taxReview.mode, always-review list, audit sampling percentage). */
public interface TaxReviewSettingsRepository {

    /** What staff chose, or {@link ReviewSettings#defaults()} (mode ALL) until someone does. */
    ReviewSettings current();

    /** Who changed it last and when; empty until someone does. */
    Optional<TaxSettingsRepository.Stamp> stamp();

    /** Stores the settings and records the change, with its reason, in the history. */
    void change(ReviewSettings to, String reason, String by, Instant at);

    /** Newest first. */
    List<ReviewSettingsChange> history(int limit);
}
