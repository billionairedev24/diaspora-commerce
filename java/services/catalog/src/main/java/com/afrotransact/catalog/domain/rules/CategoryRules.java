package com.afrotransact.catalog.domain.rules;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules of one category: its kind, the approval level and the status of every field.
 *
 * @param code        the category code ({@code dairy-eggs})
 * @param name        the category name
 * @param department  the department name
 * @param kindCode    the kind of product (PKG, PER ...)
 * @param kindName    the plain name of the kind
 * @param approval    open, approval, brand_gated, conditional_approval, provider_application or blocked
 * @param note        the category note of the matrix
 * @param provisional true for a catalog category the owner has not placed in the matrix yet
 * @param fields      every field in matrix order
 */
public record CategoryRules(String code, String name, String department, String kindCode, String kindName,
                            String approval, String note, boolean provisional, List<FieldRule> fields) {

    /** The kind used for a category without rules (a category staff created after the matrix). */
    public static final String FALLBACK_KIND = "HOM";

    public CategoryRules {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kindCode, "kindCode");
        fields = List.copyOf(fields);
        note = note == null ? "" : note;
    }

    /** The rule of a field. */
    public FieldRule rule(RuleField field) {
        return fields.stream().filter(f -> f.field() == field).findFirst()
                .orElseThrow(() -> new IllegalStateException("No rule for " + field.id() + " in " + code));
    }

    public FieldStatus status(RuleField field) {
        return rule(field).status();
    }

    public boolean shown(RuleField field) {
        return status(field).shown();
    }

    /** The label of a field as the matrix names it. */
    public String label(RuleField field) {
        return rule(field).label();
    }

    /** True for every physical product: kinds that are not digital, gift cards or services. */
    public boolean physical() {
        return shown(RuleField.PACKAGE_SIZE);
    }

    /** The fields that are hidden here, in matrix order. */
    public List<RuleField> hidden() {
        return fields.stream().filter(f -> f.status() == FieldStatus.HIDDEN).map(FieldRule::field).toList();
    }

    /** The fields of this category with one of the statuses. */
    public List<FieldRule> withStatus(Collection<FieldStatus> statuses) {
        return fields.stream().filter(f -> statuses.contains(f.status())).toList();
    }

    /** Attribute keys whose question is hidden for this category (a submitted value is ignored). */
    public Set<String> hiddenAttributeKeys() {
        return fields.stream().filter(f -> f.status() == FieldStatus.HIDDEN && f.field().attributeKey() != null)
                .map(f -> f.field().attributeKey()).collect(Collectors.toSet());
    }

    /** The document slots shown for this category. */
    public List<FieldRule> documents() {
        return fields.stream().filter(f -> f.field().isDocument() && f.status().shown()).toList();
    }

    /** The rule of a field by its id. */
    public Optional<FieldRule> ruleById(String id) {
        return fields.stream().filter(f -> f.field().id().equals(id)).findFirst();
    }

    /** The rules as a lookup by field. */
    public Map<RuleField, FieldRule> byField() {
        return fields.stream().collect(Collectors.toMap(FieldRule::field, f -> f));
    }
}
