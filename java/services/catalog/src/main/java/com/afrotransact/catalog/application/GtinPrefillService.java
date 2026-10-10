package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S031: when a seller types or scans a GTIN in the product builder, catalog looks it up in the product-data providers
 * (GS1 first when configured, then Open Food Facts for groceries) and returns what it found to prefill the form.
 */
public interface GtinPrefillService {

    /**
     * Validates the GTIN (GTIN-8/12/13/14 check digit) and returns the prefill. Needs {@code catalog:write} (sellers)
     * or {@code catalog:read}.
     *
     * @throws com.afrotransact.catalog.domain.CatalogRuleViolation when the GTIN is malformed (with a suggested fix)
     */
    GtinPrefill prefill(String gtin);

    /**
     * Prefill for the product builder. Imported fields stay editable; {@code importedFrom} marks them.
     *
     * @param gtin          cleaned digits as entered
     * @param found         at least one provider knew the GTIN; false = manual entry
     * @param existingAtin  a product page already uses this GTIN: add an offer to it instead of creating one (no
     *                      provider is called)
     * @param netContent    size / net content, e.g. "500 g"
     * @param importedFrom  field → provider ({@code GS1}, {@code OPEN_FOOD_FACTS})
     * @param categoryHints suggested categories in our taxonomy, best first
     * @param sources       providers that contributed
     * @param cached        answered from the lookup cache
     * @param message       text for the form
     */
    record GtinPrefill(String gtin, boolean found, String existingAtin, String title, String brand,
                       String netContent, String description, List<String> imageUrls,
                       Map<String, String> importedFrom, List<CategoryHint> categoryHints, List<String> sources,
                       boolean cached, String message) {
    }

    /**
     * A category suggestion resolved against the live category tree.
     *
     * @param productTypeCode product type of a leaf (null for a department-level hint)
     * @param matchedOn       provider category that matched (e.g. {@code en:rices})
     */
    record CategoryHint(UUID categoryId, String path, String level, String productTypeCode, String matchedOn) {
    }
}
