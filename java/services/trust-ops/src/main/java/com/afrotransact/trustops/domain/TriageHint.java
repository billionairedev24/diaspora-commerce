package com.afrotransact.trustops.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Advisory AI triage of a listing (ai-gateway {@code POST /v1/ai/triage}). Never decides: a human always does.
 *
 * @param riskLevel LOW, MEDIUM, HIGH or CRITICAL as reported by the model
 * @param summary   short reviewer-facing summary
 * @param flags     flags with cited fields (flags without citations are dropped by ai-gateway)
 * @param model     model that produced the hint
 */
public record TriageHint(String riskLevel, String summary, List<AiFlag> flags, String model) {

    private static final Set<String> HIGH = Set.of("HIGH", "CRITICAL");

    public TriageHint {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public boolean highRisk() {
        return riskLevel != null && HIGH.contains(riskLevel.toUpperCase(Locale.ROOT));
    }
}
