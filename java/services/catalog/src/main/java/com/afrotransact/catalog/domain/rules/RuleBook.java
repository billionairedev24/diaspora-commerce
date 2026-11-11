package com.afrotransact.catalog.domain.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All listing rules: the kinds, the fields and the category to kind assignment, resolved per category. Built once from
 * rules.json by a {@code RulesRegistry}.
 *
 * @param kinds      kind code to plain name, in matrix order
 * @param categories the rules of every category by category code
 */
public record RuleBook(Map<String, String> kinds, Map<String, CategoryRules> categories) {

    /** The code the product form asks the rules for when a category has none (a requested category). */
    public static final String GENERAL = "general";

    public RuleBook {
        kinds = Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
        categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
    }

    /** The rules of a category code; empty for a category the matrix does not know. */
    public Optional<CategoryRules> forCategory(String code) {
        return Optional.ofNullable(code == null ? null : categories.get(code));
    }

    /** The rules of a category, or the general goods rules when the category is unknown (never throws). */
    public CategoryRules forCategoryOrGeneral(String code) {
        return forCategory(code).orElseGet(() -> general(code));
    }

    /** General goods rules for a category the matrix does not list (a custom or newly created category). */
    public CategoryRules general(String code) {
        CategoryRules any = categories.values().stream().filter(c -> CategoryRules.FALLBACK_KIND.equals(c.kindCode()))
                .findFirst().orElseThrow(() -> new IllegalStateException("The rules have no general goods kind"));
        return new CategoryRules(code == null ? GENERAL : code, "General goods", "", any.kindCode(),
                any.kindName(), "open", "", true, any.fields());
    }

    /** Category codes that are in the approved matrix (not provisional). */
    public List<String> approvedCodes() {
        return categories.values().stream().filter(c -> !c.provisional()).map(CategoryRules::code).toList();
    }
}
