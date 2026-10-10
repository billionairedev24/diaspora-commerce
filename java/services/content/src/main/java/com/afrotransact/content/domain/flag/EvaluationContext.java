package com.afrotransact.content.domain.flag;

import java.util.Locale;
import java.util.Set;

/**
 * Who a flag is evaluated for.
 *
 * @param subject targeting key (user id or anonymous device id); stays in the same bucket for a flag
 * @param market  ISO country code
 * @param roles   trusted role keys of the caller
 */
public record EvaluationContext(String subject, String market, Set<String> roles) {

    public EvaluationContext {
        subject = subject == null || subject.isBlank() ? null : subject.trim();
        market = market == null || market.isBlank() ? null : market.trim().toUpperCase(Locale.ROOT);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
