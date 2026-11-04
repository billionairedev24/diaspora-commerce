package com.afrotransact.platform.events.retention;

import java.util.Optional;

/** Deletes aged rows of {@code platform_inbox} and published rows of {@code platform_outbox}. */
public interface EventRetentionService {

    /**
     * Runs one purge. Idempotent: a second run deletes only what aged out since. Does nothing when another instance is
     * purging right now or the platform tables do not exist in this database.
     *
     * @return the result, or empty when the run was skipped
     */
    Optional<PurgeResult> purge();
}
