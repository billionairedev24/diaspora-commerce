package com.afrotransact.taxreturns.domain.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates a {@link TaxProfile} over facts. Never guesses: missing or invalid required facts give MISSING_FACTS and no
 * outcome; decision rows are tried in order, first match wins; the status gate is applied after a row matched.
 * Reproduces the reference evaluator {@code tools/taxonomy/evaluator.py} (the golden scenarios pin it).
 *
 * <p>Status after a row matched: outcome BLOCKED gives BLOCKED; else the category gate (CONFLICT_OPEN, BLOCKED_BUNDLE
 * which reads as BLOCKED, NEEDS_APPROVAL, REFINE_CODE); else RESOLVED. NOT TAX OR LEGAL ADVICE: draft rules that a
 * Texas tax professional must approve before production use.
 */
public final class ProfileEvaluator {

    /**
     * @param profile   the category's profile
     * @param facts     product facts (VARIANT and STAFF owned)
     * @param behaviour seller behaviour facts (OFFER and SELLER owned)
     * @param context   system facts (CONTEXT owned), e.g. {@code exemption_window_active}
     * @param ruleVersion rule version to stamp on the result
     */
    public Resolution resolve(TaxProfile profile, Map<String, Object> facts, Map<String, Object> behaviour,
                              Map<String, Object> context, String ruleVersion) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> part : Arrays.asList(facts, behaviour, context)) {
            if (part != null) {
                part.forEach((k, v) -> {
                    if (v != null) {
                        values.put(k, v);
                    }
                });
            }
        }
        List<MissingFact> missing = missingFacts(profile, values);
        if (!missing.isEmpty()) {
            return new Resolution(ResolveStatus.MISSING_FACTS, null, null, Map.of(), null, null, missing, ruleVersion,
                    "Facts are missing; the tax is never guessed.", 0);
        }
        // Values of facts that are not required here are dropped, so a stale answer cannot steer the decision.
        Set<String> required = requiredIds(profile, values);
        Map<String, Object> used = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (required.contains(k)) {
                used.put(k, v);
            }
        });
        DecisionRow row = profile.rows().stream().filter(r -> r.matches(used)).findFirst().orElse(null);
        if (row == null) {
            return new Resolution(ResolveStatus.MISSING_FACTS, null, null, Map.of(), null, null, List.of(), ruleVersion,
                    "No rule matches these facts; escalate to tax review.", 0);
        }
        Map<String, String> codes = row.providerCodes().entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        ResolveStatus status;
        if (row.outcome() == Outcome.BLOCKED || profile.status() == ProfileStatus.BLOCKED_BUNDLE) {
            status = ResolveStatus.BLOCKED;
        } else if (profile.status() == ProfileStatus.ACTIVE) {
            status = ResolveStatus.RESOLVED;
        } else {
            status = ResolveStatus.valueOf(profile.status().name());
        }
        String reason = row.condition();
        if (status != ResolveStatus.RESOLVED) {
            String why = profile.statusReasons().stream().map(StatusReason::text).collect(Collectors.joining("; "));
            reason = row.condition() + " (" + (why.isEmpty() ? "blocked" : why) + ")";
        }
        int decision = status == ResolveStatus.RESOLVED ? 0 : profile.openDecision();
        return new Resolution(status, row.outcome(), row.internalTaxCode(), codes, row.id(), row.rateDisplay(), List.of(),
                ruleVersion, reason, decision);
    }

    /** Required facts that are absent or invalid for these values, in the profile's order. */
    public List<MissingFact> missingFacts(TaxProfile profile, Map<String, Object> values) {
        List<MissingFact> missing = new ArrayList<>();
        for (ProfileFact ref : profile.facts()) {
            if (!ref.required() || (ref.requiredWhen() != null && !ref.requiredWhen().holds(values))) {
                continue;
            }
            FactDefinition f = ref.definition();
            Object v = values.get(f.id());
            if (v == null || (v instanceof String s && s.isBlank())) {
                missing.add(new MissingFact(f.id(), f.label(), f.label() + " is needed. " + f.help()));
                continue;
            }
            String problem = invalid(f, ref, v);
            if (problem != null) {
                missing.add(new MissingFact(f.id(), f.label(), problem));
            }
        }
        return missing;
    }

    /** Fact ids that matter for these values: required, and required-when holds. */
    public Set<String> requiredIds(TaxProfile profile, Map<String, Object> values) {
        Set<String> out = new LinkedHashSet<>();
        for (ProfileFact ref : profile.facts()) {
            if (ref.required() && (ref.requiredWhen() == null || ref.requiredWhen().holds(values))) {
                out.add(ref.id());
            }
        }
        return out;
    }

    private static String invalid(FactDefinition f, ProfileFact ref, Object v) {
        switch (f.kind()) {
            case CHOICE:
                return ref.allowedChoices().contains(String.valueOf(v)) ? null
                        : f.label() + ": '" + v + "' is not one of the choices.";
            case BOOLEAN:
                return v instanceof Boolean ? null : f.label() + " must be yes or no.";
            case DECIMAL:
            case INTEGER:
            case PERCENT:
                return Condition.number(v) == null ? f.label() + " must be a number." : null;
            case FILE:
                return v instanceof String ? null : f.label() + " needs an uploaded file.";
            default:
                return null;
        }
    }
}
