package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.RewardRule;

/** Reward rules (staff-managed). */
public interface RewardRuleRepository {

    List<RewardRule> list();

    /** ACTIVE rules, optionally only those on {@code metric}. */
    List<RewardRule> active(RewardMetric metric);

    Optional<RewardRule> find(UUID id);

    void insert(RewardRule r);

    /** Optimistic locking on {@code r.version()}; false when another change won. */
    boolean update(RewardRule r);

    /** Counts one grant unless {@code max_grants} is reached (row lock); false when the rule is exhausted. */
    boolean claimGrant(UUID ruleId);
}
