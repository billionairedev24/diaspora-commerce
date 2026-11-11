package com.afrotransact.catalog.domain.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.brand.UnbrandedReason;
import com.afrotransact.catalog.domain.sizing.SizingTrait;

/**
 * A product type as one category's rules see it: the questions the rules own (brand, origin, net weight, shelf life,
 * storage, ingredients, allergens, material, model, age grade) come from the matrix instead of the per-family
 * definitions, so a hidden question is gone and a required one is required everywhere the type is used. The questions
 * only the product type knows (species, author, voltage ...) are kept as they are.
 */
public final class RuleAwareType {

    private RuleAwareType() {
    }

    /** The product type with the category's rules applied. */
    public static ProductType apply(ProductType type, CategoryRules rules) {
        Set<String> governed = governedKeys();
        Optional<AttributeDefinition> legacyBrand = type.attributes().stream()
                .filter(a -> a.kind() == AttributeKind.BRAND || "brand".equals(a.key())).findFirst();
        List<AttributeDefinition> attrs = new ArrayList<>();
        FieldRule brand = rules.rule(RuleField.BRAND);
        if (brand.status().shown()) {
            attrs.add(brandDefinition(brand, legacyBrand.orElse(null)));
        }
        for (FieldRule rule : rules.fields()) {
            AttributeDefinition def = rule.field().definition(rule.field().plainLabel(rule.label()),
                    rule.note().isEmpty() ? null : rule.note(), rule.status());
            if (def != null) {
                attrs.add(def);
            }
        }
        for (AttributeDefinition a : type.attributes()) {
            if (!governed.contains(a.key()) && a != legacyBrand.orElse(null)) {
                attrs.add(a);
            }
        }
        return new ProductType(type.code(), type.label(), attrs, type.themes(), type.identifierRule(),
                traits(type.traits(), rules));
    }

    private static ProductTypeTraits traits(ProductTypeTraits t, CategoryRules rules) {
        SizingTrait sizing = t.sizing();
        FieldStatus status = rules.status(RuleField.SIZES);
        if (sizing == null) {
            return t;
        }
        if (!status.shown()) {
            return t.withSizing(null);
        }
        boolean required = status == FieldStatus.REQUIRED
                || status == FieldStatus.OPTIONAL && Boolean.TRUE.equals(sizing.sizeChartRequired());
        return t.withSizing(new SizingTrait(required, sizing.sizeDimension(),
                sizing.suggestedMeasurements()));
    }

    private static AttributeDefinition brandDefinition(FieldRule rule, AttributeDefinition legacy) {
        String help = legacy != null && legacy.help() != null ? legacy.help() : rule.note();
        String label = legacy != null ? legacy.label() : "Brand";
        // A category that already refuses "no brand" (regulated goods such as beauty) keeps refusing it.
        List<String> reasons = legacy != null && legacy.kind() == AttributeKind.BRAND ? legacy.options()
                : List.of(UnbrandedReason.UNBRANDED.name(), UnbrandedReason.HANDMADE.name());
        return new AttributeDefinition("brand", label, reasons, rule.status() == FieldStatus.REQUIRED, false, AttributeKind.BRAND,
                List.of(), AttributeSection.DETAILS, help);
    }

    /** Attribute keys the rules own: every rule field's key and the superseded keys of the per-family definitions. */
    public static Set<String> governedKeys() {
        Set<String> keys = new HashSet<>(RuleField.SUPERSEDED_KEYS);
        for (RuleField f : RuleField.values()) {
            if (f.attributeKey() != null) {
                keys.add(f.attributeKey());
            }
        }
        keys.remove("brand");
        return keys;
    }
}
