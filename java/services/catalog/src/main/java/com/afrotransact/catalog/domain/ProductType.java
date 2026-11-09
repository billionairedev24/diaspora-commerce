package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.sizing.SizingTrait;

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

    /** The sizing trait when staff marked this type as needing sizes and size charts. */
    public Optional<SizingTrait> sizing() {
        return traits.sizingTrait();
    }

    public Optional<VariationTheme> theme(String name) {
        return themes.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /**
     * The product details the form, the upload template and the validators ask for (attributes that apply, tax facts
     * left out: those are answered per variant and per listing, see {@link #taxFacts()}).
     */
    public List<AttributeDefinition> applicableAttributes() {
        return attributes.stream().filter(a -> a.applies() && !a.taxFact()).toList();
    }

    /** The tax facts of this type (plain-word questions), in form order. */
    public List<AttributeDefinition> taxFacts() {
        return attributes.stream().filter(a -> a.applies() && a.taxFact()).toList();
    }

    /** Everything the seller form renders: the details and the tax facts. */
    public List<AttributeDefinition> formAttributes() {
        return attributes.stream().filter(AttributeDefinition::applies).toList();
    }

    /**
     * The brand attribute when this type asks for one: the applicable attribute of kind BRAND, or one keyed
     * {@code brand} (types staff created before the registry). Books and other types without one show no brand field.
     */
    public Optional<AttributeDefinition> brandAttribute() {
        return attributes.stream().filter(a -> a.applies() && (a.kind() == AttributeKind.BRAND || BrandKeys.NAME.equals(a.key())))
                .findFirst();
    }

    /** The applicable attribute with this key. */
    public Optional<AttributeDefinition> attribute(String key) {
        return attributes.stream().filter(a -> a.applies() && !a.taxFact() && a.key().equals(key)).findFirst();
    }
}
