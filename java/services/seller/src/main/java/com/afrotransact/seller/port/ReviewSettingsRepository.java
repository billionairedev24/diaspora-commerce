package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.ReviewSettings;

/** SLA, escalation and assignment settings (one row) and their audit trail. */
public interface ReviewSettingsRepository {

    ReviewSettings load();

    /** Like {@link #load()} but locks the row (round-robin cursor updates, concurrent saves). */
    ReviewSettings lock();

    void save(ReviewSettings settings);

    void audit(String actorId, String actorName, String reason, ReviewSettings before, ReviewSettings after);

    List<SettingsChange> audit(int limit);

    record SettingsChange(String actorName, String reason, Instant changedAt) {
    }
}
