package com.afrotransact.taxreturns.port;

import java.util.Collection;
import java.util.Map;

/**
 * catalog-service facts per variant ATIN: product type, perishable and the product's attributes (the seller's answers,
 * which decide the tax code). Best effort: missing answers are left out.
 */
public interface ProductFacts {

    Map<String, Facts> lookup(Collection<String> atins);

    /**
     * @param atin        variant ATIN
     * @param productType catalog product type
     * @param perishable  the product's perishable flag
     * @param attributes  the product's attribute answers (key to text)
     */
    record Facts(String atin, String productType, boolean perishable, Map<String, String> attributes) {

        public Facts {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public Facts(String atin, String productType, boolean perishable) {
            this(atin, productType, perishable, Map.of());
        }
    }
}
