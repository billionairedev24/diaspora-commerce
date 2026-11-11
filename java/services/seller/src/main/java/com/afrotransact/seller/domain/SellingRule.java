package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What it takes to sell in a scope. SELL needs nothing, APPLY needs an approval with the listed documents,
 * NOT_AVAILABLE is prohibited ({@code reason} is shown to sellers). A conditional rule (jewelry) applies only when the
 * product's material is one of {@code materials} or, with {@code watch}, when it is a watch. A rule with a
 * {@code capability} (FOOD_SERVICE) is satisfied by that capability's approval.
 *
 * @param approvalLevel   the level in tools/listing-rules/rules.json (open, brand_gated, ...) for a category rule
 * @param reapplyAfterDays staff's default wait before a disqualified seller may apply again (a suggestion on the form)
 */
public record SellingRule(UUID id, SellingScope scope, String label, SellingLevel level,
                          List<SellingDocumentRequirement> documents, String reason, String note, String approvalLevel,
                          String capability, Set<String> materials, boolean watch, int reapplyAfterDays, boolean active,
                          String source, Instant updatedAt, String updatedByName, long version) {

    /** Whether a rule applies to a listing, or cannot be told yet because the material is not known. */
    public enum Applies {
        YES, NO, UNKNOWN
    }

    public SellingRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(level, "level");
        documents = documents == null ? List.of() : List.copyOf(documents);
        materials = materials == null ? Set.of() : Set.copyOf(materials);
        label = label == null || label.isBlank() ? scope.key() : label;
    }

    public boolean conditional() {
        return !materials.isEmpty() || watch;
    }

    public Applies appliesTo(SellingFacts facts) {
        if (!conditional()) {
            return Applies.YES;
        }
        if (watch && facts.watch()) {
            return Applies.YES;
        }
        String material = facts.material();
        if (material == null) {
            return facts.watch() ? Applies.NO : Applies.UNKNOWN;
        }
        String m = material.toLowerCase(Locale.ROOT);
        boolean hit = materials.stream().anyMatch(c -> m.contains(c.toLowerCase(Locale.ROOT)));
        return hit ? Applies.YES : Applies.NO;
    }

    public SellingRule withDocuments(List<SellingDocumentRequirement> next) {
        return new SellingRule(id, scope, label, level, next, reason, note, approvalLevel, capability, materials,
                watch, reapplyAfterDays, active, source, updatedAt, updatedByName, version);
    }
}
