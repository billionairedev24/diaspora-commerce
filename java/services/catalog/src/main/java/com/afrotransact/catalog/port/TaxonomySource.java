package com.afrotransact.catalog.port;

import java.util.Map;

import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomySchemas;

/**
 * Where the importer reads the category tax dataset and the product-type schemas from: the files bundled with the
 * service ({@code tools/taxonomy/category_tax_map.json}, {@code taxonomy/schemas.json}), or a dataset staff post.
 */
public interface TaxonomySource {

    /** The dataset bundled with this build. */
    TaxonomyDataset bundled();

    /** A dataset document (the JSON of category_tax_map.json) read from a request. */
    TaxonomyDataset parse(Map<String, Object> document);

    /** Detail questions, themes and sizing of the product types. */
    TaxonomySchemas schemas();
}
