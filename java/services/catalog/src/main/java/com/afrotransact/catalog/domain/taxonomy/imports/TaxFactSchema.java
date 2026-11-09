package com.afrotransact.catalog.domain.taxonomy.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.Category;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.Choice;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.Fact;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.FactRef;
import com.afrotransact.catalog.domain.tax.TaxFactRules;

/**
 * Builds the seller-facing tax questions of a category from the dataset's facts and fact groups: typed, with units,
 * plain-language labels and help, and the expression that decides whether each is asked and required.
 *
 * <p>Facts owned by STAFF (set by tax review) and CONTEXT (worked out by the system) are never asked. A fact owned by
 * the SELLER ({@code seller_is_bakery}) is asked as part of "Your listing", since the catalog has no seller profile to
 * read it from.
 */
public final class TaxFactSchema {

    /** Owners whose facts a seller answers. */
    private static final Set<String> SELLER_ANSWERS = Set.of("VARIANT", "OFFER", "SELLER");

    private TaxFactSchema() {
    }

    public static boolean askedOfSellers(Fact fact) {
        return SELLER_ANSWERS.contains(fact.owner());
    }

    /** The tax questions of a category, in the order the dataset lists them. */
    public static List<AttributeDefinition> forCategory(TaxonomyDataset dataset, Category category) {
        List<AttributeDefinition> out = new ArrayList<>();
        for (FactRef ref : category.facts()) {
            Fact fact = dataset.fact(ref.fact());
            if (!askedOfSellers(fact)) {
                continue;
            }
            out.add(attribute(fact, ref));
        }
        return List.copyOf(out);
    }

    private static AttributeDefinition attribute(Fact fact, FactRef ref) {
        AttributeKind kind = switch (fact.kind()) {
            case "BOOLEAN" -> AttributeKind.BOOLEAN;
            case "DECIMAL" -> AttributeKind.DECIMAL;
            case "INTEGER" -> AttributeKind.INTEGER;
            case "PERCENT" -> AttributeKind.PERCENT;
            case "CHOICE" -> AttributeKind.ENUM;
            case "FILE" -> AttributeKind.FILE;
            default -> throw new IllegalArgumentException("Unknown fact kind " + fact.kind() + " of " + fact.id());
        };
        List<String> options = List.of();
        Map<String, String> labels = Map.of();
        if (kind == AttributeKind.ENUM) {
            List<Choice> all = fact.choices();
            List<Choice> offered = ref.choices().isEmpty() ? all
                    : all.stream().filter(c -> ref.choices().contains(c.value())).toList();
            options = offered.stream().map(Choice::value).toList();
            labels = offered.stream().collect(Collectors.toMap(Choice::value, Choice::label, (a, b) -> a,
                    LinkedHashMap::new));
        }
        String owner = "VARIANT".equals(fact.owner()) ? TaxFactRules.OWNER_VARIANT : TaxFactRules.OWNER_OFFER;
        String help = "SELLER".equals(fact.owner()) ? "Yes if your business is a bakery." : fact.help();
        return AttributeDefinition.taxFact(fact.id(), fact.label(), kind, fact.unit(), owner, help, options, labels,
                ref.required(), ref.requiredWhen(), fact.evidence());
    }
}
