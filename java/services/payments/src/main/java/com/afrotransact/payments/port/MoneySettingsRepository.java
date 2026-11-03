package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.MoneySettings;

/** The staff-configurable money settings and their history. */
public interface MoneySettingsRepository {

    MoneySettings get();

    /** Locks the settings row for a change. */
    MoneySettings lock();

    /** Saves the new values (optimistic version) and appends the change to the history. */
    MoneySettings save(MoneySettings next, UUID changeId, String reason, MoneySettings before);

    /** Newest first. */
    List<Change> changes(int limit);

    /** One recorded change: who, when, why and the settings before and after (JSON). */
    record Change(UUID id, String changedBy, Instant changedAt, String reason, String beforeJson, String afterJson) {
    }
}
