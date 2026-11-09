package com.afrotransact.taxreturns.domain.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The tax profile of one category: its facts, the ordered decision table and the status gate. Immutable; a change is
 * a new profile (with a new rule version).
 *
 * @param categoryCode     stable kebab-case code, e.g. {@code nuts-seeds}
 * @param name             category name
 * @param departmentCode   department code
 * @param departmentName   department name
 * @param status           the gate
 * @param statusReasons    why gated
 * @param defaultOutcome   browse hint (EXEMPT, TAXABLE, BLOCKED, CONDITIONAL), never used to charge
 * @param stripeCodes      Stripe codes the category uses
 * @param conflictingCodes the two codes of a CONFLICT_OPEN category
 * @param factGroups       fact group ids
 * @param facts            the facts the category uses
 * @param rows             the decision table
 * @param note             free note
 */
public record TaxProfile(String categoryCode, String name, String departmentCode, String departmentName,
                         ProfileStatus status, List<StatusReason> statusReasons, String defaultOutcome,
                         List<String> stripeCodes, List<String> conflictingCodes, List<String> factGroups,
                         List<ProfileFact> facts, List<DecisionRow> rows, String note) {

    public TaxProfile {
        statusReasons = statusReasons == null ? List.of() : List.copyOf(statusReasons);
        stripeCodes = stripeCodes == null ? List.of() : List.copyOf(stripeCodes);
        conflictingCodes = conflictingCodes == null ? List.of() : List.copyOf(conflictingCodes);
        factGroups = factGroups == null ? List.of() : List.copyOf(factGroups);
        facts = facts == null ? List.of() : List.copyOf(facts);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public TaxProfile withStatus(ProfileStatus next) {
        return new TaxProfile(categoryCode, name, departmentCode, departmentName, next, statusReasons, defaultOutcome,
                stripeCodes, conflictingCodes, factGroups, facts, rows, note);
    }

    public Map<String, ProfileFact> factsById() {
        return facts.stream().collect(Collectors.toMap(ProfileFact::id, Function.identity(), (a, b) -> a,
                LinkedHashMap::new));
    }

    public Optional<DecisionRow> row(String id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** Open decision number (1 to 5) behind the gate, or 0. */
    public int openDecision() {
        return statusReasons.stream().filter(r -> r.status() == status).mapToInt(StatusReason::decision).filter(d -> d > 0)
                .findFirst().orElse(0);
    }
}
