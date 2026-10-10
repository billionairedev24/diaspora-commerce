package com.afrotransact.content.domain.flag;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Targeting rules of a flag. Empty sets mean "any".
 *
 * @param markets   ISO country codes the flag applies in
 * @param roles     role keys (at least one must match)
 * @param allowList subjects that always get the flag (QA, beta testers), bypassing rollout and other rules
 */
public record FlagTargeting(Set<String> markets, Set<String> roles, Set<String> allowList) {

    public FlagTargeting {
        markets = markets == null ? Set.of() : markets.stream().map(m -> m.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        allowList = allowList == null ? Set.of() : Set.copyOf(allowList);
    }

    public static FlagTargeting none() {
        return new FlagTargeting(Set.of(), Set.of(), Set.of());
    }
}
