package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipTier;

/** The membership tiers staff define, and the append-only history of every change with its reason. */
public interface MembershipTierRepository {

    Optional<MembershipTier> find(String code);

    /** Locks the tier row until the transaction ends. */
    Optional<MembershipTier> lock(String code);

    /** Every tier, entry tier first (sort order, then code). */
    List<MembershipTier> all();

    List<MembershipTier> published();

    void insert(MembershipTier tier);

    /** Saves the tier; fails with an optimistic-lock error when the version moved. Returns the stored tier. */
    MembershipTier update(MembershipTier tier, long expectedVersion);

    void addChange(Change change);

    List<Change> changes(String tierCode, int limit);

    /**
     * One audited change.
     *
     * @param action CREATED, UPDATED, PUBLISHED or RETIRED
     */
    record Change(UUID id, String tierCode, String action, String changedBy, Instant changedAt, String reason,
                  String beforeJson, String afterJson) {
    }
}
