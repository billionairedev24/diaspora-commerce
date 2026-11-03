package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.sizing.SizeChart;

/**
 * What row validation needs to know about the catalog and the organisation.
 *
 * @param fixedType     the product type of the template used; null for the generic template (type per category)
 * @param types         every product type by code
 * @param categories    the categories rows may name (the fixed type's, or every product-type leaf)
 * @param takenSkus     the file's SKUs (upper case) that the organisation already lists
 * @param existingGtins the file's GTINs that already have a product page
 * @param existing      Update mode: the organisation's listings by SKU (upper case)
 * @param library       the organisation's library images for the file's SKUs and image file names (S310)
 * @param ownerOrg      the uploading organisation; size charts are the organisation's own
 * @param charts        the organisation's active size charts named in the file, by lower-case name
 * @param blocked       categories that exist but belong to a department the organisation may not sell in: a row
 *                      naming one is refused with a message that says how to add the department
 */
public record ImportContext(ImportMode mode, ProductType fixedType, Map<String, ProductType> types,
                            List<ImportCategory> categories, Set<String> takenSkus, Set<String> existingGtins,
                            Map<String, ExistingListing> existing, ImageLibrary library, String ownerOrg,
                            Map<String, SizeChart> charts, List<ImportCategory> blocked) {

    /** Every category is allowed. */
    public ImportContext(ImportMode mode, ProductType fixedType, Map<String, ProductType> types,
                         List<ImportCategory> categories, Set<String> takenSkus, Set<String> existingGtins,
                         Map<String, ExistingListing> existing, ImageLibrary library, String ownerOrg,
                         Map<String, SizeChart> charts) {
        this(mode, fixedType, types, categories, takenSkus, existingGtins, existing, library, ownerOrg, charts,
                List.of());
    }

    /** Without size charts. */
    public ImportContext(ImportMode mode, ProductType fixedType, Map<String, ProductType> types,
                         List<ImportCategory> categories, Set<String> takenSkus, Set<String> existingGtins,
                         Map<String, ExistingListing> existing, ImageLibrary library) {
        this(mode, fixedType, types, categories, takenSkus, existingGtins, existing, library, null, Map.of());
    }

    /** Without library images. */
    public ImportContext(ImportMode mode, ProductType fixedType, Map<String, ProductType> types,
                         List<ImportCategory> categories, Set<String> takenSkus, Set<String> existingGtins,
                         Map<String, ExistingListing> existing) {
        this(mode, fixedType, types, categories, takenSkus, existingGtins, existing, ImageLibrary.EMPTY);
    }

    public ImportContext {
        charts = charts == null ? Map.of() : Map.copyOf(charts);
        blocked = blocked == null ? List.of() : List.copyOf(blocked);
        library = library == null ? ImageLibrary.EMPTY : library;
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
