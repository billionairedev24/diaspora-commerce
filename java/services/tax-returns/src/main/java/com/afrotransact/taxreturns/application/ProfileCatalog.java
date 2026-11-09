package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import org.springframework.stereotype.Component;

/**
 * All category profiles with the rule version in force, kept in memory and rebuilt from the database at most every few
 * seconds (so a change on another replica is followed quickly) and right after a change on this one.
 */
@Component
class ProfileCatalog {

    static final Duration TTL = Duration.ofSeconds(10);

    private final TaxProfileRepository profiles;
    private final Clock clock;
    private volatile Snapshot snapshot;
    private volatile Instant expires = Instant.MIN;

    ProfileCatalog(TaxProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    Snapshot current() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s == null || now.isAfter(expires)) {
            String version = profiles.currentRuleVersion();
            if (s == null || !s.ruleVersion().equals(version)) {
                s = load(version);
            }
            snapshot = s;
            expires = now.plus(TTL);
        }
        return s;
    }

    /** Forgets what is remembered (after a change on this replica). */
    void refresh() {
        snapshot = null;
    }

    private Snapshot load(String version) {
        Map<String, TaxProfile> byCode = new LinkedHashMap<>();
        List<TaxProfile> all = profiles.all();
        all.forEach(p -> byCode.put(p.categoryCode(), p));
        return new Snapshot(version, Map.copyOf(byCode), all);
    }

    /**
     * @param ruleVersion the rule version in force
     * @param profiles    profiles by category code
     * @param ordered     profiles in department and category order
     */
    record Snapshot(String ruleVersion, Map<String, TaxProfile> profiles, List<TaxProfile> ordered) {
    }
}
