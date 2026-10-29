package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Category-specific schema for products: required details, variation themes, identifier rule and traits (food,
 * perishable, regulated, pre-order) that decide which sections the product form and upload template show.
 * The {@code custom} type is used while a requested category awaits approval.
 */
public record ProductType(String code, String label, List<AttributeDefinition> attributes,
                          List<VariationTheme> themes, IdentifierRule identifierRule, ProductTypeTraits traits) {

    public static final String CUSTOM = "custom";

    public ProductType {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        attributes = List.copyOf(attributes);
        themes = List.copyOf(themes);
        Objects.requireNonNull(identifierRule, "identifierRule");
        traits = traits == null ? ProductTypeTraits.NONE : traits;
    }

    /** A product type without traits. */
    public ProductType(String code, String label, List<AttributeDefinition> attributes, List<VariationTheme> themes,
                       IdentifierRule identifierRule) {
        this(code, label, attributes, themes, identifierRule, ProductTypeTraits.NONE);
    }

    public Optional<VariationTheme> theme(String name) {
        return themes.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** What the product form, the upload template and the validators ask for (attributes that apply). */
    public List<AttributeDefinition> applicableAttributes() {
        return attributes.stream().filter(AttributeDefinition::applies).toList();
    }

    /** The applicable attribute with this key. */
    public Optional<AttributeDefinition> attribute(String key) {
        return attributes.stream().filter(a -> a.applies() && a.key().equals(key)).findFirst();
    }
}
