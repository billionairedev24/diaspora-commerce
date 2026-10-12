package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.ProductType;

/**
 * What row validation needs to know about the catalog and the organisation.
 *
 * @param fixedType     the product type of the template used; null for the generic template (type per category)
 * @param types         every product type by code
 * @param categories    the categories rows may name (the fixed type's, or every product-type leaf)
 * @param takenSkus     the file's SKUs (upper case) that the organisation already lists
 * @param existingGtins the file's GTINs that already have a product page
 * @param existing      Update mode: the organisation's listings by SKU (upper case)
 */
public record ImportContext(ImportMode mode, ProductType fixedType, Map<String, ProductType> types,
                            List<ImportCategory> categories, Set<String> takenSkus, Set<String> existingGtins,
                            Map<String, ExistingListing> existing) {

    public ImportContext {
        types = Map.copyOf(types);
        categories = List.copyOf(categories);
        takenSkus = Set.copyOf(takenSkus);
        existingGtins = Set.copyOf(existingGtins);
        existing = Map.copyOf(existing);
    }

    /** The columns the uploaded file is read with. */
    public List<ImportColumn> columns() {
        List<String> paths = categories.stream().map(ImportCategory::path).toList();
        return fixedType == null ? ImportColumns.generic(paths) : ImportColumns.forType(fixedType, paths);
    }
}
