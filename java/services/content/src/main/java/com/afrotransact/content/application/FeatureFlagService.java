package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.flag.FeatureFlag;
import com.afrotransact.content.domain.flag.FlagTargeting;

/** Back-office management of feature flags. */
public interface FeatureFlagService {

    List<FeatureFlag> list();

    FeatureFlag get(String key);

    FeatureFlag create(String key, String description, boolean enabled, double rolloutPercentage,
                       FlagTargeting targeting);

    FeatureFlag update(String key, FlagChange change, long expectedVersion);

    /** Kill switch: forces the flag off everywhere within the cache TTL. */
    FeatureFlag killSwitch(String key, boolean on);

    /**
     * New values of a flag.
     *
     * @param description       description
     * @param enabled           enabled
     * @param rolloutPercentage 0–100
     * @param killSwitch        kill switch
     * @param targeting         targeting
     */
    record FlagChange(String description, boolean enabled, double rolloutPercentage, boolean killSwitch,
                      FlagTargeting targeting) {
    }
}
