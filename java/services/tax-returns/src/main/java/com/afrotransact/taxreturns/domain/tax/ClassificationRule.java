package com.afrotransact.taxreturns.domain.tax;

import java.util.Map;

/**
 * Maps a catalog product type, optionally narrowed by one attribute answer, to a tax code. A rule with an attribute
 * beats the rule for the type alone.
 *
 * @param id        rule id (null before it is saved)
 * @param productType catalog product type the rule is for
 * @param attrKey   product attribute that decides, or null for the type alone
 * @param attrValue attribute value that matches (case-insensitive), or null with {@code attrKey}
 * @param taxCode   resulting tax code
 * @param note      why (for the CPA's review)
 */
public record ClassificationRule(Long id, String productType, String attrKey, String attrValue, String taxCode,
                                 String note) {

    public ClassificationRule {
        if (productType == null || productType.isBlank() || taxCode == null || taxCode.isBlank()) {
            throw new IllegalArgumentException("productType and taxCode are required");
        }
        if ((attrKey == null) != (attrValue == null)) {
            throw new IllegalArgumentException("attrKey and attrValue go together");
        }
    }

    boolean matches(String type, Map<String, String> attributes) {
        if (!productType.equalsIgnoreCase(type)) {
            return false;
        }
        if (attrKey == null) {
            return true;
        }
        String answer = attributes.get(attrKey);
        return answer != null && answer.trim().equalsIgnoreCase(attrValue.trim());
    }
}
