package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What one person has done with one guided tour: the version of the tour text, how it stands and the last step seen.
 * No personal data (ids of tours and steps, a status and a time). "Not started" is the absence of a record.
 *
 * @param tourId         tour id namespaced by app, for example {@code sc.orders} or {@code st.settings}
 * @param contentVersion version of the tour text the person last dealt with (1 or more)
 * @param status         where the person left it
 * @param lastStep       the last step shown, or null
 */
public record TourProgress(UUID userId, String tourId, int contentVersion, Status status, String lastStep,
                           Instant updatedAt) {

    /** In progress (started, not finished), completed, or dismissed ("don't show again", or skipped). */
    public enum Status {
        IN_PROGRESS, COMPLETED, DISMISSED;

        /** Completed and dismissed are final for that version: a replay in progress never undoes them. */
        public boolean isFinal() {
            return this != IN_PROGRESS;
        }
    }
}
