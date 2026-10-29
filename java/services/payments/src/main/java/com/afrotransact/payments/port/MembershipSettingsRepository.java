package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipSettings;

/** The staff-set billing rules (one row) and the history of their changes. */
public interface MembershipSettingsRepository {

    MembershipSettings get();

    MembershipSettings lock();

    /** Saves the next settings if the stored version is still {@code before.version()}; records the change. */
    MembershipSettings save(MembershipSettings next, UUID changeId, String reason, MembershipSettings before);

    List<Change> changes(int limit);

    record Change(UUID id, String changedBy, Instant changedAt, String reason, String beforeJson, String afterJson) {
    }
}
