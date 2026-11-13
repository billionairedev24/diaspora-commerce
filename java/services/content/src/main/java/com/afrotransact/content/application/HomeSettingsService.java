package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.home.HomeSettings;
import com.afrotransact.content.port.HomeSettingsRepository.Stored;

/** Staff-editable settings of the dynamic home (S417): defaults, versions, audit and revert. */
public interface HomeSettingsService {

    /** The effective settings (public read: the gateway caches them for 60 s). Defaults when nothing is stored. */
    HomeSettings current();

    /** Version history, newest first (content:home.view). */
    List<Stored> versions();

    /**
     * Saves a new version (content:home.edit). The reason is mandatory; a stale expected version is a conflict; invalid
     * values are a 422 with field messages. A save that changes nothing returns the current version.
     */
    HomeSettings save(int expectedVersion, HomeSettings proposed, String reason);

    /** Makes an earlier version current again as a new version (content:home.edit); the reason is mandatory. */
    HomeSettings revert(int toVersion, String reason);
}
