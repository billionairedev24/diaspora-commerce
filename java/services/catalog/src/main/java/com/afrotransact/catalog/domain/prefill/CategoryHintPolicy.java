package com.afrotransact.catalog.domain.prefill;

import java.util.List;

/** Maps provider categories onto AfroTransact category paths (department › category › product type). */
public interface CategoryHintPolicy {

    /**
     * Category paths that match, best first (at most {@code limit}).
     *
     * @param sourceCategories provider categories, most general first (Open Food Facts order)
     */
    List<Match> match(List<String> sourceCategories, int limit);

    /**
     * A suggested category.
     *
     * @param path      AfroTransact category path, e.g. "Groceries › Pantry › Grains &amp; rice"
     * @param matchedOn the provider category that matched
     */
    record Match(String path, String matchedOn) {
    }
}
