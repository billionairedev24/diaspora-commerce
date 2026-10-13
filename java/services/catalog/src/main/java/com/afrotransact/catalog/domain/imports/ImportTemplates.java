package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;

/** Builds the template of a product type (or the generic one): columns, instructions and example rows. */
public final class ImportTemplates {

    public static final List<String> INSTRUCTIONS = List.of(
            "One row per item you sell. For sizes or colours, add one row per variant and give them the same Parent "
                    + "SKU; a product sold in one version is a single row without Parent SKU.",
            "Columns marked * are required when creating products. A product's title, description, images and "
                    + "details are read from its first row; variant rows only need SKU, options, price, quantity "
                    + "and barcode.",
            "Images: public https links to JPG, PNG or WebP files, the first being the main image (at least "
                    + "1000 × 1000 px). We copy each image into AfroTransact and check it like an upload. Or upload "
                    + "your photos to Products → Images first, named after the SKU (SKU.MAIN.jpg for the main image, "
                    + "SKU.PT01.jpg to SKU.PT08.jpg for more) and leave the cell empty, or type their file names.",
            "Upload as .xlsx or .csv: at most 10 MB and 5,000 rows per file. Create mode adds new products; Update "
                    + "mode changes listings by SKU and leaves blank cells unchanged.",
            "Keep the header row: columns are matched by their names, so you can hide or reorder them. Rows whose "
                    + "first cell starts with # are ignored.");

    private ImportTemplates() {
    }

    /** The template of a product type, with its categories in the Category dropdown. */
    public static TemplateSpec forType(ProductType type, List<ImportCategory> categories) {
        List<String> paths = categories.stream().map(ImportCategory::path).toList();
        List<ImportColumn> columns = ImportColumns.forType(type, paths);
        return new TemplateSpec(type.label(), "afrotransact-products-" + type.code(), columns, INSTRUCTIONS,
                examples(columns, type));
    }

    /** The generic template: any category, attributes and options as "name: value" pairs. */
    public static TemplateSpec generic(List<ImportCategory> categories) {
        List<String> paths = categories.stream().map(ImportCategory::path).toList();
        List<ImportColumn> columns = ImportColumns.generic(paths);
        return new TemplateSpec("All categories", "afrotransact-products", columns, INSTRUCTIONS,
                examples(columns, null));
    }

    private static List<List<String>> examples(List<ImportColumn> columns, ProductType type) {
        VariationTheme theme = type == null || type.themes().isEmpty() ? null : type.themes().getFirst();
        int variants = theme == null ? 1 : 2;
        String stem = "EXAMPLE-" + (type == null ? "ITEM" : type.code().toUpperCase(Locale.ROOT));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < variants; i++) {
            Map<String, String> options = theme == null ? Map.of() : optionsOf(theme, i);
            List<String> row = new ArrayList<>();
            for (ImportColumn c : columns) {
                row.add(cell(c, i, stem, theme, options));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String cell(ImportColumn c, int variant, String stem, VariationTheme theme,
                               Map<String, String> options) {
        if (ImportColumns.PARENT_SKU.equals(c.key())) {
            return theme == null ? "" : stem;
        }
        if (ImportColumns.SKU.equals(c.key())) {
            return theme == null ? stem : stem + "-" + (variant + 1);
        }
        if (ImportColumns.THEME.equals(c.key())) {
            return theme == null || variant > 0 ? "" : theme.name();
        }
        if (c.key().startsWith(ImportColumns.OPTION_PREFIX)) {
            return options.getOrDefault(c.key().substring(ImportColumns.OPTION_PREFIX.length()), "");
        }
        if (ImportColumns.OPTIONS.equals(c.key())) {
            return "Size: M; Color: " + (variant == 0 ? "Gold" : "Indigo");
        }
        if (ImportColumns.PRICE.equals(c.key()) || ImportColumns.QUANTITY.equals(c.key())
                || ImportColumns.GTIN.equals(c.key()) || ImportColumns.GTIN_EXEMPT.equals(c.key())
                || ImportColumns.COMPARE_AT.equals(c.key())) {
            return c.example();
        }
        return variant == 0 ? c.example() : "";
    }

    private static Map<String, String> optionsOf(VariationTheme theme, int variant) {
        Map<String, String> out = new LinkedHashMap<>();
        List<VariationDimension> dims = theme.dimensions();
        for (int d = 0; d < dims.size(); d++) {
            List<String> opts = dims.get(d).options();
            boolean last = d == dims.size() - 1;
            int pick = last ? Math.min(variant, opts.size() - 1) : 0;
            out.put(dims.get(d).name(), opts.isEmpty() ? "" : opts.get(Math.max(0, pick)));
        }
        return out;
    }
}
