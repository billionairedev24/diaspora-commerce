package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category-specific schema for products: required details, variation themes and identifier rule.
 * The {@code custom} type is used while a requested category awaits approval.
 */
public record ProductType(String code, String label, List<AttributeDefinition> attributes,
                          List<VariationTheme> themes, IdentifierRule identifierRule) {

    public static final String CUSTOM = "custom";

    public ProductType {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        attributes = List.copyOf(attributes);
        themes = List.copyOf(themes);
        Objects.requireNonNull(identifierRule, "identifierRule");
    }

    public Optional<VariationTheme> theme(String name) {
        return themes.stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
