package com.afrotransact.content.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.home.HomeSettings;

/** Versioned storage of the home settings (append only: every save and revert is a new version). */
public interface HomeSettingsRepository {

    Optional<Stored> latest();

    Optional<Stored> find(int version);

    /** Newest first. */
    List<Stored> history();

    /** Inserts the version; false when another save took that version number first. */
    boolean insert(Stored version);

    /**
     * A stored version with its audit data.
     *
     * @param settings  the settings of this version ({@code version}, {@code updatedAt}, {@code updatedBy} filled)
     * @param reason    why (mandatory)
     * @param revertOf  the version this one restored, or null
     * @param changes   changed fields against the previous version: field, from, to
     */
    record Stored(HomeSettings settings, String reason, Integer revertOf, List<Map<String, Object>> changes,
                  Instant changedAt, String changedBy) {
    }
}
