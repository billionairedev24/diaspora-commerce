package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;

/** Category tax profiles (facts, decision tables, status gate) and the rule version history. */
public interface TaxProfileRepository {

    List<TaxProfile> all();

    Optional<TaxProfile> find(String categoryCode);

    /** Who changed the profile's status last and when. */
    Optional<Stamp> stamp(String categoryCode);

    /** The rule version in force: the newest of the history. */
    String currentRuleVersion();

    /** Starts a new rule version (history is kept) and returns it. */
    String newRuleVersion(String reason, String by, Instant at);

    /** Sets the profile's status; false when the profile does not exist. */
    boolean updateStatus(String categoryCode, ProfileStatus to, String by, Instant at);

    /** Appends to the profile and code map audit. */
    void audit(AuditRow row);

    /** The audit of a category (newest first). */
    List<AuditRow> auditOf(String categoryCode, int limit);

    record Stamp(String by, Instant at) {
    }

    /**
     * @param kind PROFILE_STATUS or PROVIDER_CODE_MAP
     */
    record AuditRow(String kind, String categoryCode, String internalTaxCode, String provider, Instant at, String actor,
                    String reason, String approvedBy, String decisionRef, String ruleVersion,
                    Map<String, Object> before, Map<String, Object> after) {
    }
}
