package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;

/**
 * The columns of the bulk upload template, generated from the product type definitions so templates and the parser
 * never drift from the catalog. One row per variant: rows that share a Parent SKU are the variants of one product,
 * and product-level columns (title, description, attributes, images …) are read from the product's first row.
 */
public final class ImportColumns {

    public static final String PARENT_SKU = "parent_sku";
    public static final String SKU = "sku";
    public static final String CATEGORY = "category";
    public static final String TITLE = "title";
    public static final String BRAND = "brand";
    public static final String DESCRIPTION = "description";
    public static final String CONDITION = "condition";
    public static final String PRICE = "price";
    public static final String COMPARE_AT = "compare_at_price";
    public static final String QUANTITY = "quantity";
    public static final String GTIN = "gtin";
    public static final String GTIN_EXEMPT = "gtin_exempt";
    public static final String THEME = "variation_theme";
    public static final String OPTIONS = "options";
    public static final String ATTRIBUTES = "attributes";
    public static final String IMAGES = "image_urls";
    public static final String KEYWORDS = "keywords";
    public static final String FULFILMENT = "fulfilment";
    public static final String HANDLING = "handling_days";
    public static final String LENGTH = "package_length_in";
    public static final String WIDTH = "package_width_in";
    public static final String HEIGHT = "package_height_in";
    public static final String WEIGHT = "package_weight_lb";
    public static final String PREORDER = "preorder_eligible";
    public static final String OPTION_PREFIX = "option:";
    public static final String ATTR_PREFIX = "attr:";
    public static final String UNIT_SUFFIX = ":unit";

    public static final String YES = "Yes";
    public static final String NO = "No";
    public static final List<String> YES_NO = List.of(YES, NO);

    /** Condition labels in the template, in order; codes (USED_GOOD) are accepted too. */
    public static final Map<String, ItemCondition> CONDITIONS = conditions();
    /** Fulfilment labels in the template; codes (SELLER, AFROTRANSACT) are accepted too. */
    public static final Map<String, FulfilmentMode> FULFILMENTS = Map.of(
            "Ships from you", FulfilmentMode.SELLER, "Fulfilled by AfroTransact", FulfilmentMode.AFROTRANSACT);
    private static final List<String> FULFILMENT_LABELS = List.of("Ships from you", "Fulfilled by AfroTransact");

    private ImportColumns() {
    }

    private static Map<String, ItemCondition> conditions() {
        Map<String, ItemCondition> m = new LinkedHashMap<>();
        m.put("New", ItemCondition.NEW);
        m.put("Renewed", ItemCondition.RENEWED);
        m.put("Used - like new", ItemCondition.USED_LIKE_NEW);
        m.put("Used - very good", ItemCondition.USED_VERY_GOOD);
        m.put("Used - good", ItemCondition.USED_GOOD);
        m.put("Used - acceptable", ItemCondition.USED_ACCEPTABLE);
        return Map.copyOf(m);
    }

    /** Condition labels in display order. */
    public static List<String> conditionLabels() {
        return List.of("New", "Renewed", "Used - like new", "Used - very good", "Used - good", "Used - acceptable");
    }

    /**
     * Columns of a product type's template.
     *
     * @param categoryPaths the paths of the categories that use this product type (the Category dropdown)
     */
    public static List<ImportColumn> forType(ProductType type, List<String> categoryPaths) {
        List<ImportColumn> cols = new ArrayList<>(head(categoryPaths, categoryPaths.size() > 1));
        Optional<AttributeDefinition> brand = type.attribute(BRAND);
        cols.add(new ImportColumn(BRAND, brand.map(AttributeDefinition::label).orElse("Brand"),
                "The brand or maker shoppers search for.", brand.map(AttributeDefinition::required).orElse(false),
                ColumnKind.TEXT, List.of(), "Adire Lagos", true));
        cols.addAll(listingColumns());
        cols.addAll(variantColumns(type.identifierRule()));
        if (!type.themes().isEmpty()) {
            cols.add(new ImportColumn(THEME, "Variation", "How this product varies. Leave blank for a product "
                    + "sold in one version only (one row, no Parent SKU).", false, ColumnKind.ENUM,
                    type.themes().stream().map(VariationTheme::name).toList(), type.themes().getFirst().name(), true));
            for (VariationDimension d : dimensions(type)) {
                cols.add(new ImportColumn(OPTION_PREFIX + d.name(), d.name(), "This variant's " + d.name()
                        .toLowerCase(Locale.ROOT) + " (when the variation includes it).", false, ColumnKind.ENUM,
                        d.options(), d.options().isEmpty() ? "" : d.options().getFirst(), false));
            }
        }
        for (AttributeDefinition a : type.attributes()) {
            if (BRAND.equals(a.key())) {
                continue;
            }
            cols.add(attributeColumn(a));
            if (a.kind() == AttributeKind.NUMBER && !a.units().isEmpty()) {
                cols.add(new ImportColumn(ATTR_PREFIX + a.key() + UNIT_SUFFIX, a.label() + " unit",
                        "Unit of " + a.label().toLowerCase(Locale.ROOT) + "; " + a.units().getFirst()
                                + " when blank.", false, ColumnKind.ENUM, a.units(), a.units().getFirst(), true));
            }
        }
        cols.addAll(fulfilmentColumns());
        if (type.traits().preorder()) {
            cols.add(new ImportColumn(PREORDER, "Pre-order eligible", "Yes to let community pre-order windows sell "
                    + "this product (default Yes for food).", false, ColumnKind.BOOLEAN, YES_NO, YES, true));
        }
        return List.copyOf(cols);
    }

    /** Columns of the generic template: any category, attributes as "key: value" pairs. */
    public static List<ImportColumn> generic(List<String> categoryPaths) {
        List<ImportColumn> cols = new ArrayList<>(head(categoryPaths, true));
        cols.add(new ImportColumn(BRAND, "Brand", "The brand or maker shoppers search for.", false, ColumnKind.TEXT,
                List.of(), "Adire Lagos", true));
        cols.addAll(listingColumns());
        cols.addAll(variantColumns(IdentifierRule.GTIN));
        cols.add(new ImportColumn(THEME, "Variation", "The variation name of the category's product type, e.g. "
                + "\"Size × Color\". Blank for a product sold in one version only.", false, ColumnKind.TEXT,
                List.of(), "Size × Color", true));
        cols.add(new ImportColumn(OPTIONS, "Options", "This variant's options as Name: value pairs separated by "
                + "\";\", e.g. \"Size: M; Color: Gold\".", false, ColumnKind.TEXT, List.of(), "Size: M; Color: Gold",
                false));
        cols.add(new ImportColumn(ATTRIBUTES, "Attributes", "The product type's details as key: value pairs "
                + "separated by \";\", e.g. \"material: Cotton; fit: Regular\". Download the category's own template "
                + "to see every key and allowed value.", false, ColumnKind.LONG_TEXT, List.of(),
                "material: Cotton wax print; fit: Regular", true));
        cols.addAll(fulfilmentColumns());
        cols.add(new ImportColumn(PREORDER, "Pre-order eligible", "Food only: Yes to let community pre-order "
                + "windows sell this product.", false, ColumnKind.BOOLEAN, YES_NO, "", true));
        return List.copyOf(cols);
    }

    private static List<ImportColumn> head(List<String> categoryPaths, boolean categoryRequired) {
        return List.of(
                new ImportColumn(PARENT_SKU, "Parent SKU", "Groups the variants of one product: give every size or "
                        + "colour of the same product the same Parent SKU. Leave blank for a product without "
                        + "variations.", false, ColumnKind.TEXT, List.of(), "ANKARA-WRAP", false),
                new ImportColumn(SKU, "SKU", "Your unique code for this item (2–40 letters, digits, dot, dash or "
                        + "underscore). In Update mode, the SKU of the listing to change.", true, ColumnKind.TEXT,
                        List.of(), "ANKARA-WRAP-M-GLD", false),
                new ImportColumn(CATEGORY, "Category", "Where the product is listed. Choose from the list.",
                        categoryRequired, ColumnKind.ENUM, categoryPaths,
                        categoryPaths.isEmpty() ? "" : categoryPaths.getFirst(), true),
                new ImportColumn(TITLE, "Title", "Brand + product + key detail, 10–200 characters.", true,
                        ColumnKind.TEXT, List.of(), "Ankara Wrap Dress, Puff Sleeve", true));
    }

    private static List<ImportColumn> listingColumns() {
        List<ImportColumn> out = new ArrayList<>(baseListingColumns());
        out.addAll(out.indexOf(out.stream().filter(c -> IMAGES.equals(c.key())).findFirst().orElseThrow()) + 1,
                ImageColumns.template());
        return out;
    }

    private static List<ImportColumn> baseListingColumns() {
        return List.of(
                new ImportColumn(DESCRIPTION, "Description", "What shoppers should know: fit, feel, use, care. "
                        + "Up to 5,000 characters.", false, ColumnKind.LONG_TEXT, List.of(),
                        "Hand-finished wax print wrap dress with puff sleeves.", true),
                new ImportColumn(CONDITION, "Condition", "New unless stated.", false, ColumnKind.ENUM,
                        conditionLabels(), "New", true),
                new ImportColumn(IMAGES, "Image URLs", "Public https links to JPG, PNG or WebP images (at least "
                        + "1000 × 1000 px for the first), or file names from your image library, separated by spaces, "
                        + "new lines or \"|\". The first is the main image; up to 10. Leave empty to use the library "
                        + "images named after the SKU (SKU.MAIN.jpg, SKU.PT01.jpg …).", true,
                        ColumnKind.URLS, List.of(), "https://example.com/ankara-front.jpg", true),
                new ImportColumn(KEYWORDS, "Search keywords", "Words shoppers might search for, separated by commas "
                        + "(up to 20).", false, ColumnKind.TEXT, List.of(), "ankara, wax print, owambe", true));
    }

    private static List<ImportColumn> variantColumns(IdentifierRule rule) {
        boolean isbn = rule == IdentifierRule.ISBN;
        return List.of(
                new ImportColumn(PRICE, "Price (USD)", "Selling price in US dollars, e.g. 24.99.", true,
                        ColumnKind.MONEY, List.of(), "49.00", false),
                new ImportColumn(COMPARE_AT, "Compare-at price (USD)", "Optional \"was\" price shown struck "
                        + "through; must be higher than the price.", false, ColumnKind.MONEY, List.of(), "", false),
                new ImportColumn(QUANTITY, "Quantity", "Units you have to sell (0–100,000). Ignored for Fulfilled "
                        + "by AfroTransact: stock comes from your inbound shipments.", true, ColumnKind.INTEGER,
                        List.of(), "12", false),
                new ImportColumn(GTIN, isbn ? "ISBN-13" : "Barcode (GTIN/UPC/EAN)", isbn
                        ? "The book's ISBN-13 (978 or 979 prefix)." : "The manufacturer barcode (8, 12, 13 or 14 "
                        + "digits). Leave blank and answer Yes to \"No barcode\" for handmade or private-label "
                        + "items.", isbn, ColumnKind.GTIN, List.of(), isbn ? "9780141439518" : "", false),
                new ImportColumn(GTIN_EXEMPT, "No barcode", "Yes when the item has no manufacturer barcode "
                        + "(handmade, tailored, private label).", false, ColumnKind.BOOLEAN, YES_NO,
                        isbn ? NO : YES, false));
    }

    private static List<ImportColumn> fulfilmentColumns() {
        return List.of(
                new ImportColumn(FULFILMENT, "Fulfilment", "Who ships orders: you, or AfroTransact from a "
                        + "fulfilment centre.", false, ColumnKind.ENUM, FULFILMENT_LABELS, "Ships from you", true),
                new ImportColumn(HANDLING, "Handling time (days)", "Business days from order to dispatch, 1–30 "
                        + "(default 2).", false, ColumnKind.INTEGER, List.of(), "2", true),
                new ImportColumn(LENGTH, "Package length (in)", "Packed size in inches. Required for Fulfilled by "
                        + "AfroTransact.", false, ColumnKind.DECIMAL, List.of(), "12", true),
                new ImportColumn(WIDTH, "Package width (in)", "Packed width in inches.", false, ColumnKind.DECIMAL,
                        List.of(), "10", true),
                new ImportColumn(HEIGHT, "Package height (in)", "Packed height in inches.", false,
                        ColumnKind.DECIMAL, List.of(), "2", true),
                new ImportColumn(WEIGHT, "Package weight (lb)", "Packed weight in pounds.", false,
                        ColumnKind.DECIMAL, List.of(), "1.2", true));
    }

    private static ImportColumn attributeColumn(AttributeDefinition a) {
        ColumnKind kind = switch (a.kind()) {
            case ENUM -> ColumnKind.ENUM;
            case NUMBER -> ColumnKind.DECIMAL;
            case MULTILINE -> ColumnKind.LONG_TEXT;
            case TEXT -> ColumnKind.TEXT;
        };
        String description = a.help() != null ? a.help() + "." : a.label() + ".";
        if (a.kind() == AttributeKind.ENUM) {
            description += " Choose from the list.";
        }
        String example = a.kind() == AttributeKind.ENUM && !a.options().isEmpty() ? a.options().getFirst()
                : a.kind() == AttributeKind.NUMBER ? "12" : "";
        return new ImportColumn(ATTR_PREFIX + a.key(), a.label(), description, a.required(), kind, a.options(),
                example, true);
    }

    /** Every dimension used by the type's themes, once, in theme order (the option columns). */
    public static List<VariationDimension> dimensions(ProductType type) {
        Map<String, Set<String>> options = new LinkedHashMap<>();
        for (VariationTheme t : type.themes()) {
            for (VariationDimension d : t.dimensions()) {
                options.computeIfAbsent(d.name(), k -> new LinkedHashSet<>()).addAll(d.options());
            }
        }
        List<VariationDimension> out = new ArrayList<>();
        options.forEach((name, opts) -> out.add(new VariationDimension(name, List.copyOf(opts))));
        return out;
    }

    /** Header text in its comparable form: lower case, no trailing "*", single spaces. */
    public static String normalise(String header) {
        if (header == null) {
            return "";
        }
        String h = header.replace(' ', ' ').strip();
        while (h.endsWith("*")) {
            h = h.substring(0, h.length() - 1).strip();
        }
        return h.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Maps column keys to their position in the uploaded header. A header cell matches a column by key or label
     * (case-insensitive, trailing "*" ignored); the first match wins.
     */
    public static Map<String, Integer> resolve(List<String> header, List<ImportColumn> columns) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (ImportColumn c : columns) {
            byName.putIfAbsent(normalise(c.key()), c.key());
            byName.putIfAbsent(normalise(c.label()), c.key());
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = byName.get(normalise(header.get(i)));
            if (key != null) {
                out.putIfAbsent(key, i);
            }
        }
        return out;
    }
}
