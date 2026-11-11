package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.brand.UnbrandedReason;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldControl;
import com.afrotransact.catalog.domain.rules.FieldStatus;
import com.afrotransact.catalog.domain.rules.RuleAwareType;
import com.afrotransact.catalog.domain.rules.RuleField;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.SizingTrait;

/**
 * The columns of the bulk upload template, generated from the product type definitions so templates and the parser
 * never drift from the catalog. One row per variant: rows that share a Parent SKU are the variants of one product,
 * and product-level columns (title, description, attributes, images …) are read from the product's first row.
 */
public final class ImportColumns {

    public static final String PARENT_SKU = "parent_sku";
    public static final String SKU = "sku";
    public static final String ATIN = "atin";
    /** Prefix of the template columns of the category's tax questions: {@code fact:net_weight_oz}. */
    public static final String FACT_PREFIX = "fact:";
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
    /** Link or library file name of the label photo (ingredients and nutrition panel, Supplement Facts, Drug Facts). */
    public static final String LABEL_PHOTO = "label_photo_url";
    public static final String DANGEROUS = "dangerous_goods";
    /** "Field: reason" pairs for the fields the seller cannot provide where the rules allow an exemption. */
    public static final String EXEMPTIONS = "why_not_provided";
    public static final String SIZE_CHART = "size_chart";
    public static final String FIT_NOTE = "fit_note";
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
    /** Fit note labels in the template; codes (RUNS_SMALL) are accepted too. */
    public static final Map<String, FitNote> FIT_NOTES = Map.of("Runs small", FitNote.RUNS_SMALL,
            "True to size", FitNote.TRUE_TO_SIZE, "Runs large", FitNote.RUNS_LARGE);
    public static final List<String> FIT_LABELS = List.of("Runs small", "True to size", "Runs large");
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
     * Columns of the template of a product type used by these categories, from the listing rules: a column is shown when
     * any of the categories asks the question and marked required when all of them require it. Falls back to
     * {@link #forType} when the categories carry no rules (test fixtures).
     */
    public static List<ImportColumn> forCategories(ProductType type, List<ImportCategory> categories) {
        List<String> paths = categories.stream().map(ImportCategory::path).toList();
        List<CategoryRules> rules = categories.stream().map(ImportCategory::rules).filter(Objects::nonNull).toList();
        if (rules.isEmpty() || rules.size() != categories.size()) {
            return forType(type, paths);
        }
        return forRules(type, paths, rules);
    }

    /** What the categories of a template together ask: a question is shown if any asks it, required if all require it. */
    private record Agg(List<CategoryRules> rules) {

        boolean shown(RuleField f) {
            return rules.stream().anyMatch(r -> r.shown(f));
        }

        boolean required(RuleField f) {
            return rules.stream().allMatch(r -> r.status(f) == FieldStatus.REQUIRED);
        }

        boolean allShown(RuleField f) {
            return rules.stream().allMatch(r -> r.shown(f));
        }

        String note(RuleField f) {
            return rules.getFirst().rule(f).note();
        }

        String label(RuleField f) {
            return f.plainLabel(rules.getFirst().label(f));
        }
    }

    private static List<ImportColumn> forRules(ProductType type, List<String> paths, List<CategoryRules> rules) {
        Agg a = new Agg(rules);
        List<ImportColumn> cols = new ArrayList<>(head(paths, paths.size() > 1));
        if (a.shown(RuleField.BRAND)) {
            String base = a.note(RuleField.BRAND).isEmpty() ? "The brand shoppers search for." : a.note(RuleField.BRAND);
            cols.add(new ImportColumn(BRAND, type.brandAttribute().map(AttributeDefinition::label).orElse("Brand"), base + " Write \"No brand\" or \"Handmade\" if it has none. A brand "
                    + "we do not know yet is added and reviewed by AfroTransact.", a.required(RuleField.BRAND),
                    ColumnKind.TEXT, List.of(), "Adire Lagos", true));
        }
        cols.addAll(ruleListingColumns(a));
        cols.addAll(ruleVariantColumns(type.identifierRule(), a));
        if (!type.themes().isEmpty()) {
            cols.add(new ImportColumn(THEME, "Variation", "How this product varies. Leave blank for a product "
                    + "sold in one version only (one row, no Parent SKU).", false, ColumnKind.ENUM,
                    type.themes().stream().map(VariationTheme::name).toList(), type.themes().getFirst().name(), true));
            for (VariationDimension d : dimensions(type)) {
                cols.add(optionColumn(d));
            }
        }
        rules.stream().map(r -> RuleAwareType.apply(type, r)).filter(t -> t.sizing().isPresent()).findFirst()
                .ifPresent(t -> cols.addAll(sizingColumns(t, t.sizing().orElseThrow())));
        Set<String> governed = RuleAwareType.governedKeys();
        for (AttributeDefinition d : type.applicableAttributes()) {
            if (d.kind() == AttributeKind.BRAND || BRAND.equals(d.key()) || governed.contains(d.key())) {
                continue;
            }
            addAttribute(cols, d);
        }
        for (RuleField f : RuleField.values()) {
            if (f.attributeKey() == null || f == RuleField.BRAND || !a.shown(f)) {
                continue;
            }
            AttributeDefinition d = f.definition(a.label(f), help(a, f), a.required(f) ? FieldStatus.REQUIRED
                    : FieldStatus.OPTIONAL);
            addAttribute(cols, d);
        }
        if (rules.stream().anyMatch(ImportColumns::asksExemptions)) {
            cols.add(new ImportColumn(EXEMPTIONS, "Why not provided", "Only where a question allows it: the question "
                    + "and why it does not apply, separated by \";\", e.g. \"Material: handmade, mixed\". Leave blank "
                    + "when you answered everything.", false, ColumnKind.LONG_TEXT, List.of(),
                    "", true));
        }
        cols.addAll(factColumns(type));
        cols.addAll(ruleFulfilmentColumns(a));
        if (type.traits().preorder()) {
            cols.add(new ImportColumn(PREORDER, "Pre-order eligible", "Yes to let community pre-order windows sell "
                    + "this product (default Yes for food).", false, ColumnKind.BOOLEAN, YES_NO, YES, true));
        }
        return List.copyOf(cols);
    }

    private static String help(Agg a, RuleField f) {
        String note = a.note(f);
        String base = note.isEmpty() ? null : note;
        if (f.control() == FieldControl.MULTI_CHOICE) {
            return (base == null ? "" : base + " ") + "Choose from: " + String.join(", ", f.options())
                    + " (separate with commas) or None.";
        }
        return base;
    }

    private static boolean asksExemptions(CategoryRules r) {
        return r.fields().stream().anyMatch(f -> f.status() == FieldStatus.REQUIRED_OR_EXEMPTION && !f.field().isDocument()
                && f.field() != RuleField.PRODUCT_ID && f.field() != RuleField.BRAND && f.field() != RuleField.SIZES
                && f.field() != RuleField.CONDITION);
    }

    private static void addAttribute(List<ImportColumn> cols, AttributeDefinition a) {
        cols.add(attributeColumn(a));
        if (a.kind() == AttributeKind.NUMBER && !a.units().isEmpty()) {
            cols.add(new ImportColumn(ATTR_PREFIX + a.key() + UNIT_SUFFIX, a.label() + " unit",
                    "Unit of " + a.label().toLowerCase(Locale.ROOT) + "; " + a.units().getFirst()
                            + " when blank.", false, ColumnKind.ENUM, a.units(), a.units().getFirst(), true));
        }
    }

    private static List<ImportColumn> ruleListingColumns(Agg a) {
        List<ImportColumn> out = new ArrayList<>();
        out.add(new ImportColumn(DESCRIPTION, "Description", "What shoppers should know: fit, feel, use, care. "
                + "Up to 5,000 characters.", a.required(RuleField.DESCRIPTION), ColumnKind.LONG_TEXT, List.of(),
                "Hand-finished wax print wrap dress with puff sleeves.", true));
        if (a.shown(RuleField.CONDITION)) {
            out.add(new ImportColumn(CONDITION, "Condition", "New unless stated. Used and renewed only where the "
                    + "category allows it; otherwise the item is listed as New.", false, ColumnKind.ENUM,
                    conditionLabels(), "New", true));
        }
        out.add(new ImportColumn(KEYWORDS, "Search keywords", "Words shoppers might search for, separated by commas "
                + "(up to 20).", false, ColumnKind.TEXT, List.of(), "ankara, wax print, owambe", true));
        out.add(new ImportColumn(IMAGES, "Image URLs", "Public https links to JPG, PNG or WebP images, or file names "
                + "from your image library, separated by spaces, new lines or \"|\". The first is the main image; up to 10. "
                + "Leave empty to use the library images named after the SKU (SKU.MAIN.jpg, SKU.PT01.jpg …).", true,
                ColumnKind.URLS, List.of(), "https://example.com/ankara-front.jpg", true));
        out.addAll(ImageColumns.template());
        if (a.shown(RuleField.LABEL_PHOTO)) {
            out.add(new ImportColumn(LABEL_PHOTO, "Label photo URL", labelHelp(a), a.required(RuleField.LABEL_PHOTO),
                    ColumnKind.TEXT, List.of(), "https://example.com/label.jpg", true));
        }
        if (a.shown(RuleField.DANGEROUS_GOODS)) {
            out.add(new ImportColumn(DANGEROUS, "Dangerous goods", "Yes if the item is an aerosol, flammable, battery, "
                    + "candle or cleaner. A Yes keeps it out of Fulfilled by AfroTransact until staff review it.",
                    a.required(RuleField.DANGEROUS_GOODS), ColumnKind.BOOLEAN, YES_NO, NO, true));
        }
        return out;
    }

    private static String labelHelp(Agg a) {
        return "A photo of the label: the ingredients and nutrition panel, the Supplement Facts or Drug Facts, or the "
                + "ingredient list. A public https link or a library file name." + (a.required(RuleField.LABEL_PHOTO)
                ? "" : " Required in some categories.");
    }

    private static List<ImportColumn> ruleVariantColumns(IdentifierRule rule, Agg a) {
        boolean isbn = rule == IdentifierRule.ISBN;
        List<ImportColumn> out = new ArrayList<>();
        out.add(new ImportColumn(PRICE, "Price (USD)", "Selling price in US dollars, e.g. 24.99.", true,
                ColumnKind.MONEY, List.of(), "49.00", false));
        out.add(new ImportColumn(COMPARE_AT, "Compare-at price (USD)", "Optional \"was\" price shown struck "
                + "through; must be higher than the price.", false, ColumnKind.MONEY, List.of(), "", false));
        if (a.shown(RuleField.QUANTITY)) {
            out.add(new ImportColumn(QUANTITY, "Quantity", "Units you have to sell (0–100,000). Ignored for Fulfilled "
                    + "by AfroTransact: stock comes from your inbound shipments.", true, ColumnKind.INTEGER,
                    List.of(), "12", false));
        }
        if (a.shown(RuleField.PRODUCT_ID)) {
            boolean needed = isbn || a.required(RuleField.PRODUCT_ID);
            out.add(new ImportColumn(GTIN, isbn ? "ISBN-13" : "Barcode (GTIN/UPC/EAN)", isbn
                    ? "The book's ISBN-13 (978 or 979 prefix)." : "The manufacturer barcode (8, 12, 13 or 14 digits)."
                    + (needed ? "" : " Leave blank and answer Yes to \"No barcode\" for handmade or private-label items."),
                    needed, ColumnKind.GTIN, List.of(), isbn ? "9780141439518" : "", false));
            if (!isbn && rulesAllowNoBarcode(a)) {
                out.add(new ImportColumn(GTIN_EXEMPT, "No barcode", "Yes when the item has no manufacturer barcode "
                        + "(handmade, tailored, private label).", false, ColumnKind.BOOLEAN, YES_NO, YES, false));
            }
        }
        return out;
    }

    private static boolean rulesAllowNoBarcode(Agg a) {
        return a.rules().stream().anyMatch(r -> r.status(RuleField.PRODUCT_ID) == FieldStatus.REQUIRED_OR_EXEMPTION
                || r.status(RuleField.PRODUCT_ID) == FieldStatus.OPTIONAL);
    }

    private static List<ImportColumn> ruleFulfilmentColumns(Agg a) {
        List<ImportColumn> out = new ArrayList<>();
        out.add(new ImportColumn(FULFILMENT, "Fulfilment", "Who ships orders: you, or AfroTransact from a "
                + "fulfilment centre.", false, ColumnKind.ENUM, FULFILMENT_LABELS, "Ships from you", true));
        if (a.shown(RuleField.HANDLING_DAYS)) {
            out.add(new ImportColumn(HANDLING, "Handling time (days)", "Business days from order to dispatch, 1–30 "
                    + "(default 2).", false, ColumnKind.INTEGER, List.of(), "2", true));
        }
        if (a.shown(RuleField.PACKAGE_SIZE)) {
            boolean all = a.allShown(RuleField.PACKAGE_SIZE);
            String per = " Of the box as shipped, for this row; leave all four empty to use the first row of the "
                    + "product. Required for every physical item.";
            out.add(new ImportColumn(LENGTH, "Package length (in)", "Packed length in inches (at most 108)." + per, all,
                    ColumnKind.DECIMAL, List.of(), "12", false));
            out.add(new ImportColumn(WIDTH, "Package width (in)", "Packed width in inches (at most 108).", false,
                    ColumnKind.DECIMAL, List.of(), "10", false));
            out.add(new ImportColumn(HEIGHT, "Package height (in)", "Packed height in inches (at most 108).", false,
                    ColumnKind.DECIMAL, List.of(), "2", false));
            out.add(new ImportColumn(WEIGHT, "Package weight (lb)", "Packed weight in pounds (at most 150).", false,
                    ColumnKind.DECIMAL, List.of(), "1.2", false));
        }
        return out;
    }

    /**
     * Columns of a product type's template (no listing rules: fixtures and legacy callers).
     *
     * @param categoryPaths the paths of the categories that use this product type (the Category dropdown)
     */
    public static List<ImportColumn> forType(ProductType type, List<String> categoryPaths) {
        List<ImportColumn> cols = new ArrayList<>(head(categoryPaths, categoryPaths.size() > 1));
        type.brandAttribute().ifPresent(brand -> cols.add(new ImportColumn(BRAND, brand.label(), brandHelp(brand),
                brand.required(), ColumnKind.TEXT, List.of(), "Adire Lagos", true)));
        cols.addAll(listingColumns());
        cols.addAll(variantColumns(type.identifierRule()));
        if (!type.themes().isEmpty()) {
            cols.add(new ImportColumn(THEME, "Variation", "How this product varies. Leave blank for a product "
                    + "sold in one version only (one row, no Parent SKU).", false, ColumnKind.ENUM,
                    type.themes().stream().map(VariationTheme::name).toList(), type.themes().getFirst().name(), true));
            for (VariationDimension d : dimensions(type)) {
                cols.add(optionColumn(d));
            }
        }
        type.sizing().ifPresent(sizing -> cols.addAll(sizingColumns(type, sizing)));
        for (AttributeDefinition a : type.applicableAttributes()) {
            if (a.kind() == AttributeKind.BRAND || BRAND.equals(a.key())) {
                continue;
            }
            cols.add(attributeColumn(a));
            if (a.kind() == AttributeKind.NUMBER && !a.units().isEmpty()) {
                cols.add(new ImportColumn(ATTR_PREFIX + a.key() + UNIT_SUFFIX, a.label() + " unit",
                        "Unit of " + a.label().toLowerCase(Locale.ROOT) + "; " + a.units().getFirst()
                                + " when blank.", false, ColumnKind.ENUM, a.units(), a.units().getFirst(), true));
            }
        }
        cols.addAll(factColumns(type));
        cols.addAll(fulfilmentColumns());
        if (type.traits().preorder()) {
            cols.add(new ImportColumn(PREORDER, "Pre-order eligible", "Yes to let community pre-order windows sell "
                    + "this product (default Yes for food).", false, ColumnKind.BOOLEAN, YES_NO, YES, true));
        }
        return List.copyOf(cols);
    }

    /** The Size / Color style column of a variation dimension; a dropdown that also takes the seller's own values. */
    private static ImportColumn optionColumn(VariationDimension d) {
        String what = d.name().toLowerCase(Locale.ROOT);
        String example = d.options().isEmpty() ? "" : d.options().getFirst();
        if (d.allowCustomValues()) {
            return new ImportColumn(OPTION_PREFIX + d.name(), d.name(), "This variant's " + what + " (when the "
                    + "variation includes it). Pick a suggestion or type your own, e.g. " + customExample(d) + ".",
                    false, ColumnKind.SUGGESTED, d.options(), example, false);
        }
        return new ImportColumn(OPTION_PREFIX + d.name(), d.name(), "This variant's " + what + " (when the variation "
                + "includes it).", false, ColumnKind.ENUM, d.options(), example, false);
    }

    private static String customExample(VariationDimension d) {
        String lower = d.name().toLowerCase(Locale.ROOT);
        return lower.contains("color") || lower.contains("colour") ? "\"Indigo adire\"" : "\"UK 12\", \"Free size\"";
    }

    /**
     * One column per tax question the seller answers (plain words, with the unit in the label): the product facts of
     * every row, and the seller's behaviour answers read from the product's first row. Proof files are not a column:
     * they are uploaded for each item after the upload.
     */
    private static List<ImportColumn> factColumns(ProductType type) {
        List<ImportColumn> out = new ArrayList<>();
        for (AttributeDefinition a : type.taxFacts()) {
            if (a.kind() == AttributeKind.FILE) {
                continue;
            }
            String help = (a.help() == null ? a.label() : a.help()) + (a.required() ? "" : " Optional.");
            String when = a.requiredWhen() == null ? "" : " Needed only when it applies to your item.";
            boolean variantLevel = "VARIANT".equals(a.owner());
            switch (a.kind()) {
                case BOOLEAN -> out.add(new ImportColumn(FACT_PREFIX + a.key(), a.label(), help + when, false,
                        ColumnKind.BOOLEAN, YES_NO, NO, variantLevel ? false : true));
                case ENUM -> out.add(new ImportColumn(FACT_PREFIX + a.key(), a.label(), help + when + " Choose from the list.",
                        false, ColumnKind.ENUM, a.options().stream().map(a::optionLabel).toList(),
                        a.optionLabel(a.options().getFirst()), !variantLevel));
                case INTEGER -> out.add(new ImportColumn(FACT_PREFIX + a.key(), a.label(), help + when + " A whole number.",
                        false, ColumnKind.INTEGER, List.of(), "2", !variantLevel));
                default -> out.add(new ImportColumn(FACT_PREFIX + a.key(), a.label(), help + when + " A number"
                        + (a.unit() == null ? "." : " in " + a.unit() + "."), false, ColumnKind.DECIMAL, List.of(), "8.5",
                        !variantLevel));
            }
        }
        return out;
    }

    /** The size chart columns of a sized product type's template. */
    private static List<ImportColumn> sizingColumns(ProductType type, SizingTrait sizing) {
        return List.of(
                new ImportColumn(SIZE_CHART, "Size chart", "The name of one of your size charts. Every size in this "
                        + "product's rows needs a row in the chart." + (sizing.sizeChartRequired() ? "" : " Optional "
                        + "for this category.") + " Create charts first on the Size charts page or with the size "
                        + "chart upload.", sizing.sizeChartRequired(), ColumnKind.TEXT, List.of(),
                        type.label() + " chart", true),
                new ImportColumn(FIT_NOTE, "Size fit", "How the sizes fit: Runs small, True to size or Runs large. "
                        + "Blank uses the size chart's own fit note.", false, ColumnKind.ENUM, FIT_LABELS,
                        "True to size", true));
    }

    private static String brandHelp(AttributeDefinition brand) {
        String base = brand.help() != null ? brand.help() : "The brand shoppers search for.";
        String reasons = brand.unbrandedReasons().isEmpty() ? ""
                : " Write \"" + (brand.unbrandedReasons().contains(UnbrandedReason.UNBRANDED) ? "No brand" : "Handmade")
                + "\" if it has none.";
        return base + reasons + " A brand we do not know yet is added and reviewed by AfroTransact.";
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
        cols.add(new ImportColumn(SIZE_CHART, "Size chart", "Sized categories (fashion): the name of one of your size "
                + "charts.", false, ColumnKind.TEXT, List.of(), "", true));
        cols.add(new ImportColumn(FIT_NOTE, "Size fit", "How the sizes fit: Runs small, True to size or Runs large.", false,
                ColumnKind.ENUM, FIT_LABELS, "", true));
        cols.add(new ImportColumn(LABEL_PHOTO, "Label photo URL", "Food, health, beauty and pet food: a photo of the "
                + "label (ingredients and nutrition panel, Supplement Facts, Drug Facts). Required in those categories.",
                false, ColumnKind.TEXT, List.of(), "", true));
        cols.add(new ImportColumn(DANGEROUS, "Dangerous goods", "Beauty, health, electronics, candles, cleaners and "
                + "toys with batteries: Yes for an aerosol, flammable, battery, candle or cleaner. Required there.", false,
                ColumnKind.BOOLEAN, YES_NO, "", true));
        cols.add(new ImportColumn(EXEMPTIONS, "Why not provided", "Where a category allows it: the question and why "
                + "it does not apply, separated by \";\", e.g. \"Material: handmade, mixed\".", false,
                ColumnKind.LONG_TEXT, List.of(), "", true));
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
                new ImportColumn(SKU, "SKU", "Your own code for this item (2–40 letters, digits, dot, dash or "
                        + "underscore). Optional: leave it empty and we give the item a code. In Update mode, the SKU of "
                        + "the listing to change; when empty, the barcode or the ATIN finds it.", false, ColumnKind.TEXT,
                        List.of(), "ANKARA-WRAP-M-GLD", false),
                new ImportColumn(ATIN, "ATIN", "AfroTransact's code for the item, from your SKU list. Only needed in "
                        + "Update mode when a row has neither a SKU nor a barcode.", false, ColumnKind.TEXT, List.of(),
                        "", false),
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
                new ImportColumn(IMAGES, "Image URLs", "Public https links to JPG, PNG or WebP images, or file names from your image library, separated by spaces, "
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
            case BOOLEAN -> ColumnKind.BOOLEAN;
            case INTEGER -> ColumnKind.INTEGER;
            case DECIMAL, PERCENT -> ColumnKind.DECIMAL;
            case TEXT, BRAND, FILE -> ColumnKind.TEXT;
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
        Set<String> custom = new LinkedHashSet<>();
        for (VariationTheme t : type.themes()) {
            for (VariationDimension d : t.dimensions()) {
                options.computeIfAbsent(d.name(), k -> new LinkedHashSet<>()).addAll(d.options());
                if (d.allowCustomValues()) {
                    custom.add(d.name());
                }
            }
        }
        List<VariationDimension> out = new ArrayList<>();
        options.forEach((name, opts) -> out.add(new VariationDimension(name, List.copyOf(opts), custom.contains(name))));
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
