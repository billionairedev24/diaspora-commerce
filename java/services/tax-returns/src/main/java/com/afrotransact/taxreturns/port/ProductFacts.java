package com.afrotransact.taxreturns.port;

import java.util.Collection;
import java.util.Map;

/** catalog-service facts per variant ATIN (product type, perishable). Best effort: missing answers are left out. */
public interface ProductFacts {

    Map<String, Facts> lookup(Collection<String> atins);

    record Facts(String atin, String productType, boolean perishable) {
    }
}
