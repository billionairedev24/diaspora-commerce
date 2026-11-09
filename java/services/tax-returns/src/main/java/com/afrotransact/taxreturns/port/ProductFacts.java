package com.afrotransact.taxreturns.port;

import java.util.Collection;
import java.util.Map;

/**
 * catalog-service facts per variant ATIN: product type, perishable, the product's attributes and, for the category tax
 * build, the tax category and the typed tax facts. Best effort: missing answers are left out.
 */
public interface ProductFacts {

    Map<String, Facts> lookup(Collection<String> atins);

    /**
     * @param atin            variant ATIN
     * @param productType     catalog product type
     * @param perishable      the product's perishable flag
     * @param attributes      the product's attribute answers (key to text)
     * @param categoryCode    tax profile (category) code, null while catalog does not report it
     * @param taxFacts        the variant's typed tax facts by fact id (owner VARIANT)
     * @param sellerBehaviour the owner listing's behaviour facts (owner OFFER)
     */
    record Facts(String atin, String productType, boolean perishable, Map<String, String> attributes,
                 String categoryCode, Map<String, Object> taxFacts, Map<String, Object> sellerBehaviour) {

        public Facts {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            taxFacts = taxFacts == null ? Map.of() : Map.copyOf(taxFacts);
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }

        public Facts(String atin, String productType, boolean perishable, Map<String, String> attributes) {
            this(atin, productType, perishable, attributes, null, Map.of(), Map.of());
        }

        public Facts(String atin, String productType, boolean perishable) {
            this(atin, productType, perishable, Map.of());
        }
    }
}
