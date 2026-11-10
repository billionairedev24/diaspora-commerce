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
     * @param ownerOrgId      the organisation that owns the catalog listing (null when catalog does not say): the
     *                        behaviour is that organisation's, never another seller's
     */
    record Facts(String atin, String productType, boolean perishable, Map<String, String> attributes,
                 String categoryCode, Map<String, Object> taxFacts, Map<String, Object> sellerBehaviour,
                 String ownerOrgId) {

        public Facts {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            taxFacts = taxFacts == null ? Map.of() : Map.copyOf(taxFacts);
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }

        public Facts(String atin, String productType, boolean perishable, Map<String, String> attributes) {
            this(atin, productType, perishable, attributes, null, Map.of(), Map.of(), null);
        }

        public Facts(String atin, String productType, boolean perishable, Map<String, String> attributes,
                     String categoryCode, Map<String, Object> taxFacts, Map<String, Object> sellerBehaviour) {
            this(atin, productType, perishable, attributes, categoryCode, taxFacts, sellerBehaviour, null);
        }

        public Facts(String atin, String productType, boolean perishable) {
            this(atin, productType, perishable, Map.of());
        }
    }
}
