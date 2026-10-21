package com.afrotransact.taxreturns.domain.tax;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides an item's tax code from its catalog product type and the seller's answers, using staff-maintained rules.
 * Anything not positively classified is general taxable goods (under-collecting is our liability; over-collecting is
 * refunded). Pure and immutable: build a new one when the rules change.
 */
public final class TaxClassifier {

    private final Map<String, TaxCode> codes = new HashMap<>();
    private final List<ClassificationRule> rules;
    private final TaxCode fallback;

    public TaxClassifier(List<TaxCode> codes, List<ClassificationRule> rules) {
        codes.forEach(c -> this.codes.put(c.code(), c));
        this.fallback = this.codes.getOrDefault(TaxCode.GENERAL_GOODS,
                new TaxCode(TaxCode.GENERAL_GOODS, "General goods", TaxCategory.GENERAL, null, true));
        this.rules = rules.stream()
                .filter(r -> this.codes.containsKey(r.taxCode()) && this.codes.get(r.taxCode()).active())
                .sorted(Comparator.comparing((ClassificationRule r) -> r.attrKey() == null))
                .toList();
    }

    /** Classifies by product type and the product's attributes (keys as in the catalog). */
    public TaxClassification classify(String productType, Map<String, String> attributes) {
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        if (productType != null) {
            String type = productType.trim().toLowerCase(Locale.ROOT);
            for (ClassificationRule r : rules) {
                if (r.matches(type, attrs)) {
                    TaxCode c = codes.get(r.taxCode());
                    return new TaxClassification(c.code(), c.category(), c.providerCode(),
                            r.attrKey() == null ? TaxClassification.Basis.TYPE : TaxClassification.Basis.ANSWER);
                }
            }
        }
        return new TaxClassification(fallback.code(), fallback.category(), fallback.providerCode(),
                TaxClassification.Basis.DEFAULT);
    }

    public List<TaxCode> codes() {
        return codes.values().stream().sorted(Comparator.comparing(TaxCode::code)).toList();
    }
}
