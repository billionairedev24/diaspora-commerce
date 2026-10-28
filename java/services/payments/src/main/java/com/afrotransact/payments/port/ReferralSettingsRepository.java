package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.ReferralSettings;

/** The staff-configurable referral settings and their history. */
public interface ReferralSettingsRepository {

    ReferralSettings get();

    /** Locks the settings row for a change. */
    ReferralSettings lock();

    /** Saves the new values (optimistic version) and appends the change, with its reason, to the history. */
    ReferralSettings save(ReferralSettings next, UUID changeId, String reason, ReferralSettings before);

    /** Newest first. */
    List<Change> changes(int limit);

    /** One recorded change: who, when, why and the settings before and after (JSON). */
    record Change(UUID id, String changedBy, Instant changedAt, String reason, String beforeJson, String afterJson) {
    }
}
