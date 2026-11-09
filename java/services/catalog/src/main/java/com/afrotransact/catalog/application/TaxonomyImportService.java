package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;

/**
 * Loads the department > category > product type tree and the tax questions of every category from the category tax
 * dataset ({@code tools/taxonomy/category_tax_map.json}). Idempotent and audited: running it twice changes nothing.
 *
 * <p>What it owns: the tax profile id and tax questions of a category, and nodes and product types that do not exist
 * yet. What staff own and it leaves alone unless {@code force} is set: names, placement and order of existing nodes, a
 * category's status once set (tax-returns and the tax screens change it), and the detail questions of a product type.
 */
public interface TaxonomyImportService {

    /**
     * Staff import (needs {@code catalog:taxonomy}).
     *
     * @param document a posted dataset (the JSON of category_tax_map.json); null imports the bundled one
     * @param dryRun   report what would change, write nothing
     * @param force    also reset names, placement, status and detail questions to the dataset
     * @param reason   why, kept in the audit trail (at least 3 characters)
     */
    Report run(Map<String, Object> document, boolean dryRun, boolean force, String reason);

    /** The same import for the service itself (start-up seed path, CLI, tests): no permission check, audited as the system. */
    Report runAsSystem(boolean force, String reason);

    /**
     * What an import did (or would do).
     *
     * @param createdNodes     nodes added (departments, categories, product-type leaves)
     * @param updatedNodes     existing nodes changed (tax profile, status when forced, placement when forced)
     * @param createdTypes     product types added
     * @param updatedTypes     product types whose tax questions (or, when forced, schema) changed
     * @param unchangedNodes   nodes already as planned
     * @param notes            what was deliberately left alone, for staff
     */
    record Report(String ruleVersion, boolean dryRun, boolean force, int departments, int categories, int productTypes,
                  int createdNodes, int updatedNodes, int unchangedNodes, int createdTypes, int updatedTypes,
                  List<String> notes) {
    }
}
