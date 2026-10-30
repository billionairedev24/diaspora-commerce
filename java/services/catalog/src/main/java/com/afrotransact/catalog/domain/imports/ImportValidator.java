package com.afrotransact.catalog.domain.imports;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.ProductDraftPolicy;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.brand.UnbrandedReason;

/**
 * Validates every row of an uploaded sheet and reads the products it describes. Messages name the column, so the
 * preview can mark the cell and the error report can explain each row. A product is importable only when all of its
 * rows are: variants of one product are created together.
 */
public final class ImportValidator {

    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9._-]{1,39}");
    private static final Pattern MONEY = Pattern.compile("^\\$?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?$");
    private static final Pattern DECIMAL_COMMA = Pattern.compile("^\\$?\\s*(\\d+),(\\d{1,2})$");
    private static final Pattern INTEGER = Pattern.compile("^\\d+(?:\\.0+)?$");
    private static final Pattern URL_SPLIT = Pattern.compile("[\\s|]+");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_PRICE_MINOR = 100_000_000L;
    private static final Set<String> TRUE = Set.of("yes", "y", "true", "1", "oui", "si", "sí", "bẹẹni");
    private static final Set<String> FALSE = Set.of("no", "n", "false", "0", "non", "rara");

    private final ImportContext ctx;
    private final List<ImportColumn> columns;
    private Map<String, Integer> index;
    private final Map<Integer, List<RowMessage>> messages = new LinkedHashMap<>();
    /** Rows whose SKU repeats an earlier row: never grouped with it. */
    private final Set<Integer> duplicateRows = new HashSet<>();

    private ImportValidator(ImportContext ctx) {
        this.ctx = ctx;
        this.columns = ctx.columns();
    }

    /** Validates the sheet. Throws {@link SpreadsheetRejected} when the file cannot be imported at all. */
    public static ValidationResult validate(ParsedSheet sheet, ImportContext ctx) {
        return new ImportValidator(ctx).run(sheet);
    }

    private ValidationResult run(ParsedSheet sheet) {
        index = ImportColumns.resolve(sheet.header(), columns);
        List<String> fileWarnings = new ArrayList<>();
        checkHeader(sheet.header(), fileWarnings);
        if (sheet.rows().isEmpty()) {
            throw new SpreadsheetRejected("The file has no product rows under the header");
        }
        if (sheet.rows().size() > ImportLimits.MAX_ROWS) {
            throw new SpreadsheetRejected("The file has " + sheet.rows().size() + " rows; upload at most "
                    + ImportLimits.MAX_ROWS + " rows per file");
        }
        sheet.rows().forEach(r -> messages.put(r.rowNumber(), new ArrayList<>()));
        for (SheetRow r : sheet.rows()) {
            for (int i = 0; i < r.cells().size(); i++) {
                if (r.cell(i).length() > ImportLimits.MAX_CELL_CHARS) {
                    add(r, RowMessage.error(keyAt(i), i, "Longer than " + ImportLimits.MAX_CELL_CHARS + " characters"));
                }
            }
        }
        Map<Integer, String> skus = checkSkus(sheet.rows());
        Map<String, List<SheetRow>> groups = new LinkedHashMap<>();
        Map<Integer, String> groupOf = new HashMap<>();
        for (SheetRow r : sheet.rows()) {
            String key = groupKey(r, skus.get(r.rowNumber()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            groupOf.put(r.rowNumber(), key);
        }
        List<ImportedProduct> products = new ArrayList<>();
        Map<String, ImportedProduct> byGroup = new HashMap<>();
        for (Map.Entry<String, List<SheetRow>> g : groups.entrySet()) {
            ImportedProduct p = ctx.mode() == ImportMode.CREATE ? createGroup(g.getKey(), g.getValue(), skus)
                    : updateGroup(g.getKey(), g.getValue(), skus);
            if (p != null) {
                byGroup.put(g.getKey(), p);
            }
        }
        if (ctx.mode() == ImportMode.CREATE) {
            checkGtinsAcrossFile(byGroup.values());
        }
        // Variants of a product are imported together: one bad row holds back its siblings.
        for (Map.Entry<String, List<SheetRow>> g : groups.entrySet()) {
            List<SheetRow> bad = g.getValue().stream().filter(this::hasError).toList();
            if (!bad.isEmpty() && g.getValue().size() > 1) {
                for (SheetRow r : g.getValue()) {
                    if (!hasError(r)) {
                        add(r, RowMessage.error(ImportColumns.PARENT_SKU, idx(ImportColumns.PARENT_SKU), "Another variant "
                                + "of this product has errors (row " + bad.getFirst().rowNumber()
                                + "); variants are imported together"));
                    }
                }
            }
            if (bad.isEmpty() && byGroup.containsKey(g.getKey())) {
                products.add(byGroup.get(g.getKey()));
            }
        }
        List<ValidatedRow> rows = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            List<RowMessage> ms = messages.get(r.rowNumber());
            RowStatus status = ms.stream().anyMatch(m -> m.severity() == Severity.ERROR) ? RowStatus.ERROR
                    : ms.isEmpty() ? RowStatus.VALID : RowStatus.WARNING;
            rows.add(new ValidatedRow(r.rowNumber(), groupOf.get(r.rowNumber()), skus.getOrDefault(r.rowNumber(), ""),
                    status, ms, r.cells()));
        }
        return new ValidationResult(sheet.header(), fileWarnings, rows, products, groups.size());
    }

    // ------------------------------------------------------------------ header

    private void checkHeader(List<String> header, List<String> warnings) {
        if (header.size() > ImportLimits.MAX_COLUMNS) {
            throw new SpreadsheetRejected("The file has " + header.size() + " columns; at most "
                    + ImportLimits.MAX_COLUMNS + " are read");
        }
        List<String> missing = new ArrayList<>();
        for (ImportColumn c : columns) {
            boolean needed = ctx.mode() == ImportMode.CREATE ? c.required() : ImportColumns.SKU.equals(c.key());
            if (needed && !index.containsKey(c.key()) && !c.key().startsWith(ImportColumns.ATTR_PREFIX)
                    && !ImportColumns.BRAND.equals(c.key()) && !ImportColumns.IMAGES.equals(c.key())) {
                missing.add(c.header());
            }
        }
        if (!missing.isEmpty()) {
            throw new SpreadsheetRejected("Missing required column" + (missing.size() > 1 ? "s" : "") + ": "
                    + String.join(", ", missing) + ". Download the template again and keep its header row.");
        }
        Set<Integer> known = new HashSet<>(index.values());
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            if (!known.contains(i) && !header.get(i).isBlank()) {
                unknown.add(header.get(i));
            }
        }
        if (!unknown.isEmpty()) {
            warnings.add("Ignored column" + (unknown.size() > 1 ? "s" : "") + " not in the template: "
                    + String.join(", ", unknown));
        }
        Set<String> seen = new HashSet<>();
        for (String h : header) {
            String n = ImportColumns.normalise(h);
            if (!n.isEmpty() && !seen.add(n)) {
                warnings.add("Column \"" + h + "\" appears twice; the first one is used");
            }
        }
    }

    // ------------------------------------------------------------------ SKUs

    private Map<Integer, String> checkSkus(List<SheetRow> rows) {
        Map<Integer, String> out = new HashMap<>();
        Map<String, Integer> firstRow = new HashMap<>();
        for (SheetRow r : rows) {
            String sku = text(r, ImportColumns.SKU).toUpperCase(Locale.ROOT);
            out.put(r.rowNumber(), sku);
            if (sku.isEmpty()) {
                add(r, err(ImportColumns.SKU, "Required"));
                continue;
            }
            if (!SKU.matcher(sku).matches()) {
                add(r, err(ImportColumns.SKU, "2–40 letters, digits, dot, dash or underscore"));
                continue;
            }
            Integer first = firstRow.putIfAbsent(sku, r.rowNumber());
            if (first != null) {
                add(r, err(ImportColumns.SKU, "Duplicate SKU: also on row " + first));
                duplicateRows.add(r.rowNumber());
                continue;
            }
            if (ctx.mode() == ImportMode.CREATE && ctx.takenSkus().contains(sku)) {
                add(r, err(ImportColumns.SKU, "You already list this SKU; upload it in Update mode to change it"));
            }
            if (ctx.mode() == ImportMode.UPDATE && !ctx.existing().containsKey(sku)) {
                add(r, err(ImportColumns.SKU, "No listing has this SKU; upload it in Create mode to add it"));
            }
        }
        return out;
    }

    private String groupKey(SheetRow r, String sku) {
        if (ctx.mode() == ImportMode.UPDATE) {
            ExistingListing e = ctx.existing().get(sku);
            return e == null ? "row-" + r.rowNumber() : e.productId().toString();
        }
        String parent = text(r, ImportColumns.PARENT_SKU).toUpperCase(Locale.ROOT);
        if (parent.isEmpty() && duplicateRows.contains(r.rowNumber())) {
            return "row-" + r.rowNumber();
        }
        String key = parent.isEmpty() ? sku : parent;
        if (key.isEmpty()) {
            return "row-" + r.rowNumber();
        }
        return key.length() > 60 ? key.substring(0, 60) : key;
    }

    // ------------------------------------------------------------------ create

    private ImportedProduct createGroup(String key, List<SheetRow> rows, Map<Integer, String> skus) {
        SheetRow first = rows.getFirst();
        if (rows.size() > ImportLimits.MAX_VARIANTS) {
            rows.forEach(r -> add(r, err(ImportColumns.PARENT_SKU, "A product has at most "
                    + ImportLimits.MAX_VARIANTS + " variants")));
            return null;
        }
        ImportCategory category = category(first, true);
        ProductType type = category == null ? ctx.fixedType()
                : Optional.ofNullable(ctx.types().get(category.productTypeCode())).orElse(ctx.fixedType());
        if (type == null) {
            return null;
        }
        warnIfDifferent(rows, ImportColumns.CATEGORY);
        String title = productText(rows, ImportColumns.TITLE);
        if (title.isEmpty()) {
            add(first, err(ImportColumns.TITLE, "Required"));
        } else if (title.length() < ProductDraftPolicy.MIN_TITLE || title.length() > ProductDraftPolicy.MAX_TITLE) {
            add(first, err(ImportColumns.TITLE, "Between " + ProductDraftPolicy.MIN_TITLE + " and "
                    + ProductDraftPolicy.MAX_TITLE + " characters"));
        }
        Map<String, String> attributes = attributes(rows, type, true);
        List<String> images = images(rows, true);
        ProductDetails d = details(rows, type);
        String theme = productText(rows, ImportColumns.THEME);
        VariationTheme vt = null;
        if (!theme.isEmpty()) {
            vt = type.themes().stream().filter(t -> t.name().equalsIgnoreCase(theme)).findFirst().orElse(null);
            if (vt == null) {
                add(first, err(ImportColumns.THEME, type.themes().isEmpty() ? "This category has no variations"
                        : "Choose one of " + type.themes().stream().map(VariationTheme::name).toList()));
            }
        } else if (rows.size() > 1) {
            rows.forEach(r -> add(r, err(ImportColumns.THEME, "Set the Variation for a product with several "
                    + "variants (rows sharing Parent SKU " + key + ")")));
        }
        warnIfDifferent(rows, ImportColumns.THEME);
        List<ImportedVariant> variants = new ArrayList<>();
        Set<String> combos = new HashSet<>();
        for (SheetRow r : rows) {
            Map<String, String> options = options(r, type, vt);
            if (vt != null && options.size() == vt.dimensions().size() && !combos.add(options.toString())) {
                add(r, err(optionColumn(vt.dimensions().getLast().name()), "Same options as another variant of "
                        + "this product"));
            }
            variants.add(variant(r, skus.get(r.rowNumber()), options, type, d.fulfilment(), true));
        }
        return new ImportedProduct(key, null, category, type.code(), title, d.description(), attributes, images,
                d.keywords(), d.condition(), d.fulfilment(), d.handlingDays(), d.packaging(), d.preorder(),
                vt == null ? "" : vt.name(), variants);
    }

    private ImportCategory category(SheetRow r, boolean create) {
        String raw = text(r, ImportColumns.CATEGORY);
        List<ImportCategory> cats = ctx.categories();
        if (raw.isEmpty()) {
            if (cats.size() == 1 && ctx.fixedType() != null) {
                return cats.getFirst();
            }
            if (create) {
                add(r, err(ImportColumns.CATEGORY, "Required: choose the category from the list"));
            }
            return null;
        }
        String wanted = normalPath(raw);
        Optional<ImportCategory> match = cats.stream().filter(c -> normalPath(c.path()).equals(wanted)).findFirst();
        if (match.isEmpty()) {
            // A unique last segment ("Dresses") is enough.
            List<ImportCategory> byName = cats.stream().filter(c -> {
                String p = normalPath(c.path());
                return p.endsWith("> " + wanted) || p.substring(p.lastIndexOf('>') + 1).strip().equals(wanted);
            }).toList();
            match = byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
        }
        if (match.isEmpty()) {
            add(r, err(ImportColumns.CATEGORY, ctx.fixedType() == null ? "Unknown category; choose one from the "
                    + "Category list" : "Not a category of this template; download the template of that category"));
            return null;
        }
        return match.get();
    }

    private static String normalPath(String p) {
        return p.replace('›', '>').replaceAll("\\s*>\\s*", " > ").strip().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ update

    private ImportedProduct updateGroup(String key, List<SheetRow> rows, Map<Integer, String> skus) {
        SheetRow first = rows.getFirst();
        ExistingListing existing = ctx.existing().get(skus.get(first.rowNumber()));
        if (existing == null) {
            return null;
        }
        ProductType type = ctx.types().get(existing.productTypeCode());
        if (type == null) {
            add(first, err(ImportColumns.SKU, "The listing's product type is no longer available"));
            return null;
        }
        for (String ignored : List.of(ImportColumns.CATEGORY, ImportColumns.PARENT_SKU, ImportColumns.THEME,
                ImportColumns.GTIN, ImportColumns.GTIN_EXEMPT, ImportColumns.FULFILMENT)) {
            for (SheetRow r : rows) {
                if (!text(r, ignored).isEmpty()) {
                    add(r, RowMessage.warning(ignored, idx(ignored), "Ignored in Update mode"));
                }
            }
        }
        for (SheetRow r : rows) {
            for (VariationDimension dim : ImportColumns.dimensions(type)) {
                if (!text(r, optionColumn(dim.name())).isEmpty()) {
                    add(r, RowMessage.warning(optionColumn(dim.name()), idx(optionColumn(dim.name())),
                            "Ignored in Update mode"));
                }
            }
        }
        String title = productText(rows, ImportColumns.TITLE);
        if (!title.isEmpty() && (title.length() < ProductDraftPolicy.MIN_TITLE
                || title.length() > ProductDraftPolicy.MAX_TITLE)) {
            add(first, err(ImportColumns.TITLE, "Between " + ProductDraftPolicy.MIN_TITLE + " and "
                    + ProductDraftPolicy.MAX_TITLE + " characters"));
        }
        Map<String, String> attributes = attributes(rows, type, false);
        List<String> images = images(rows, false);
        ProductDetails d = details(rows, type);
        List<ImportedVariant> variants = new ArrayList<>();
        for (SheetRow r : rows) {
            variants.add(variant(r, skus.get(r.rowNumber()), Map.of(), type, d.fulfilment(), false));
        }
        return new ImportedProduct(key, existing.productId(), null, type.code(), title.isEmpty() ? null : title,
                d.description(), attributes, images, d.keywords(), d.condition(), d.fulfilment(), d.handlingDays(),
                d.packaging(), d.preorder(), null, variants);
    }

    // ------------------------------------------------------------------ product-level values

    /**
     * The brand cell, judged like the product form does: a type without a brand attribute ignores it, "No brand" or
     * "Handmade" stand for a reason (only where the type allows them) instead of a made-up brand, and a name is kept
     * as typed for the writer to match to the registry (or add as a pending brand).
     */
    private void brand(List<SheetRow> rows, ProductType type, boolean create, Map<String, String> out) {
        SheetRow first = rows.getFirst();
        String raw = productText(rows, ImportColumns.BRAND);
        Optional<AttributeDefinition> def = type.brandAttribute();
        if (def.isEmpty()) {
            if (!raw.isEmpty()) {
                add(first, RowMessage.warning(ImportColumns.BRAND, idx(ImportColumns.BRAND),
                        "Ignored: " + type.label() + " have no brand"));
            }
            return;
        }
        AttributeDefinition d = def.get();
        boolean mayLeave = !d.unbrandedReasons().isEmpty();
        if (raw.isEmpty()) {
            if (create && d.required()) {
                add(first, err(ImportColumns.BRAND, mayLeave ? "Required. Write \"No brand\" or \"Handmade\" if it has none"
                        : "Required"));
            }
            return;
        }
        Optional<UnbrandedReason> reason = UnbrandedReason.ofText(raw);
        if (reason.isPresent()) {
            if (d.unbrandedReasons().contains(reason.get())) {
                out.put(BrandKeys.REASON, reason.get().name());
            } else {
                add(first, err(ImportColumns.BRAND, "Name the brand; this category does not accept items without one"));
            }
            return;
        }
        String problem = d.problem(raw);
        if (problem != null) {
            add(first, err(ImportColumns.BRAND, problem));
        } else {
            out.put(ImportColumns.BRAND, raw.trim());
        }
    }

    private Map<String, String> attributes(List<SheetRow> rows, ProductType type, boolean create) {
        SheetRow first = rows.getFirst();
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> generic = ctx.fixedType() == null ? pairs(first, ImportColumns.ATTRIBUTES) : Map.of();
        brand(rows, type, create, out);
        for (AttributeDefinition a : type.applicableAttributes()) {
            if (a.kind() == AttributeKind.BRAND || BrandKeys.NAME.equals(a.key())) {
                continue;
            }
            String col = ImportColumns.ATTR_PREFIX + a.key();
            String value = ctx.fixedType() == null ? generic.getOrDefault(a.key().toLowerCase(Locale.ROOT), "")
                    : productText(rows, col);
            if (ctx.fixedType() != null && a.kind() == AttributeKind.NUMBER && !value.isEmpty()) {
                String unit = productText(rows, col + ImportColumns.UNIT_SUFFIX);
                if (!unit.isEmpty()) {
                    value = value + " " + unit;
                }
            }
            if (value.isEmpty() && !create) {
                continue;
            }
            String problem = a.problem(value);
            if (problem != null) {
                String column = ctx.fixedType() == null ? ImportColumns.ATTRIBUTES : col;
                add(first, err(column, (ctx.fixedType() == null ? a.label() + ": " : "") + problem));
            } else if (!value.isEmpty()) {
                out.put(a.key(), a.normalise(value));
            }
            if (ctx.fixedType() != null) {
                warnIfDifferent(rows, col);
            }
        }
        if (ctx.fixedType() == null) {
            Set<String> known = new HashSet<>();
            type.applicableAttributes().forEach(a -> known.add(a.key().toLowerCase(Locale.ROOT)));
            List<String> unknown = generic.keySet().stream().filter(k -> !known.contains(k)).toList();
            if (!unknown.isEmpty()) {
                add(first, RowMessage.warning(ImportColumns.ATTRIBUTES, idx(ImportColumns.ATTRIBUTES),
                        "Ignored attributes not used by " + type.label() + ": " + String.join(", ", unknown)));
            }
        }
        return out;
    }

    /**
     * The product's images: links and library file names from the image cell, or, when the cell is empty, the library
     * images named after the Parent SKU or a row's SKU ({@code SKU.MAIN.jpg}, {@code SKU.PT01.jpg} …). Library images
     * are returned as {@code library:<assetId>} references.
     */
    private List<String> images(List<SheetRow> rows, boolean required) {
        SheetRow first = rows.getFirst();
        String raw = productText(rows, ImportColumns.IMAGES);
        if (raw.isEmpty()) {
            if (!required) {
                return List.of();
            }
            List<String> matched = skuImages(rows);
            if (matched.isEmpty()) {
                add(first, err(ImportColumns.IMAGES, "Add image links or library file names, or upload images "
                        + "named after the SKU (SKU.MAIN.jpg) to your image library"));
            }
            return matched;
        }
        List<String> entries = Arrays.stream(URL_SPLIT.split(raw)).map(String::strip).filter(s -> !s.isEmpty())
                .distinct().toList();
        if (entries.size() > ImportLimits.MAX_IMAGES) {
            add(first, err(ImportColumns.IMAGES, "Up to " + ImportLimits.MAX_IMAGES + " images per product"));
        }
        List<String> out = new ArrayList<>();
        for (String u : entries) {
            if (ImageLibrary.isFileName(u)) {
                LibraryImage img = ctx.library().byFileName(u).orElse(null);
                if (img == null) {
                    add(first, err(ImportColumns.IMAGES, "Not in your image library: " + abbreviate(u)
                            + " (upload it on the Images page)"));
                } else if (img.held()) {
                    add(first, err(ImportColumns.IMAGES, abbreviate(u) + " is held for review by moderation"));
                } else if (img.ready()) {
                    out.add(ImageLibrary.ref(img));
                }
                continue;
            }
            String problem = urlProblem(u);
            if (problem != null) {
                add(first, err(ImportColumns.IMAGES, problem + ": " + abbreviate(u)));
            } else {
                String path = URI.create(u).getPath().toLowerCase(Locale.ROOT);
                int dot = path.lastIndexOf('.');
                if (dot > path.lastIndexOf('/') && !IMAGE_EXTENSIONS.contains(path.substring(dot + 1))) {
                    add(first, RowMessage.warning(ImportColumns.IMAGES, idx(ImportColumns.IMAGES),
                            "Not a JPG, PNG or WebP link; it may be refused: " + abbreviate(u)));
                }
                out.add(u);
            }
        }
        return out;
    }

    /** Library images named after the Parent SKU, else the first row SKU that has some; held images are skipped. */
    private List<String> skuImages(List<SheetRow> rows) {
        List<String> candidates = new ArrayList<>();
        candidates.add(productText(rows, ImportColumns.PARENT_SKU));
        rows.forEach(r -> candidates.add(text(r, ImportColumns.SKU)));
        for (String sku : candidates) {
            List<LibraryImage> found = ctx.library().productImages(sku);
            if (found.isEmpty()) {
                continue;
            }
            if (found.stream().noneMatch(LibraryImage::ready)) {
                add(rows.getFirst(), err(ImportColumns.IMAGES, "The library images of " + sku
                        + " are held for review by moderation"));
                return List.of();
            }
            return found.stream().filter(LibraryImage::ready).map(ImageLibrary::ref).toList();
        }
        return List.of();
    }

    /** Why a URL can't be fetched, or null. Reachability is checked when the image is copied during the import. */
    public static String urlProblem(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            return "Not a valid link";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "Use an https:// link";
        }
        if (uri.getHost() == null || !uri.getHost().contains(".") || uri.getUserInfo() != null) {
            return "Not a public web address";
        }
        return null;
    }

    private record ProductDetails(String description, List<String> keywords, ItemCondition condition,
                                  FulfilmentMode fulfilment, Integer handlingDays, PackageDimensions packaging,
                                  Boolean preorder) {
    }

    private ProductDetails details(List<SheetRow> rows, ProductType type) {
        SheetRow first = rows.getFirst();
        String description = productText(rows, ImportColumns.DESCRIPTION);
        if (description.length() > ListingDetails.MAX_DESCRIPTION) {
            add(first, err(ImportColumns.DESCRIPTION, "At most " + ListingDetails.MAX_DESCRIPTION + " characters"));
        } else if (description.isEmpty() && ctx.mode() == ImportMode.CREATE) {
            add(first, RowMessage.warning(ImportColumns.DESCRIPTION, idx(ImportColumns.DESCRIPTION),
                    "No description: listings with one convert better"));
        }
        String kw = productText(rows, ImportColumns.KEYWORDS);
        List<String> keywords = kw.isEmpty() ? null : Arrays.stream(kw.split("[,;]")).map(String::strip)
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (keywords != null && keywords.size() > ListingDetails.MAX_KEYWORDS) {
            add(first, err(ImportColumns.KEYWORDS, "Up to " + ListingDetails.MAX_KEYWORDS + " keywords"));
        } else if (keywords != null && keywords.stream().anyMatch(k -> k.length() > ListingDetails.MAX_KEYWORD)) {
            add(first, err(ImportColumns.KEYWORDS, "Each keyword at most " + ListingDetails.MAX_KEYWORD
                    + " characters"));
        }
        ItemCondition condition = null;
        String c = productText(rows, ImportColumns.CONDITION);
        if (!c.isEmpty()) {
            condition = lookup(ImportColumns.CONDITIONS, c, ItemCondition.class);
            if (condition == null) {
                add(first, err(ImportColumns.CONDITION, "Choose one of " + ImportColumns.conditionLabels()));
            }
        }
        FulfilmentMode fulfilment = null;
        String f = productText(rows, ImportColumns.FULFILMENT);
        if (!f.isEmpty() && ctx.mode() == ImportMode.CREATE) {
            fulfilment = lookup(ImportColumns.FULFILMENTS, f, FulfilmentMode.class);
            if (fulfilment == null) {
                add(first, err(ImportColumns.FULFILMENT, "Choose Ships from you or Fulfilled by AfroTransact"));
            }
        }
        Integer handling = null;
        String h = productText(rows, ImportColumns.HANDLING);
        if (!h.isEmpty()) {
            handling = integer(h);
            if (handling == null || handling < 1 || handling > ListingDetails.MAX_HANDLING_DAYS) {
                add(first, err(ImportColumns.HANDLING, "A whole number of days from 1 to "
                        + ListingDetails.MAX_HANDLING_DAYS));
                handling = null;
            }
        }
        PackageDimensions packaging = packaging(rows, fulfilment == FulfilmentMode.AFROTRANSACT);
        Boolean preorder = null;
        String p = productText(rows, ImportColumns.PREORDER);
        if (!p.isEmpty()) {
            preorder = bool(p);
            if (preorder == null) {
                add(first, err(ImportColumns.PREORDER, "Yes or No"));
            } else if (!type.traits().preorder()) {
                add(first, RowMessage.warning(ImportColumns.PREORDER, idx(ImportColumns.PREORDER),
                        "Ignored: only food can be sold in pre-order windows"));
                preorder = null;
            }
        }
        for (String col : List.of(ImportColumns.DESCRIPTION, ImportColumns.KEYWORDS, ImportColumns.CONDITION,
                ImportColumns.FULFILMENT, ImportColumns.HANDLING, ImportColumns.IMAGES, ImportColumns.TITLE,
                ImportColumns.BRAND)) {
            warnIfDifferent(rows, col);
        }
        return new ProductDetails(description.isEmpty() ? null : description, keywords, condition, fulfilment,
                handling, packaging, preorder);
    }

    private PackageDimensions packaging(List<SheetRow> rows, boolean required) {
        SheetRow first = rows.getFirst();
        String[] cols = {ImportColumns.LENGTH, ImportColumns.WIDTH, ImportColumns.HEIGHT, ImportColumns.WEIGHT};
        double[] v = new double[4];
        int filled = 0;
        boolean bad = false;
        for (int i = 0; i < cols.length; i++) {
            String s = productText(rows, cols[i]);
            if (s.isEmpty()) {
                continue;
            }
            filled++;
            BigDecimal n = decimal(s);
            if (n == null || n.signum() <= 0) {
                add(first, err(cols[i], "A number above zero"));
                bad = true;
            } else {
                v[i] = n.doubleValue();
            }
        }
        if (filled == 0) {
            if (required) {
                add(first, err(ImportColumns.LENGTH, "Package size and weight are required for Fulfilled by "
                        + "AfroTransact"));
            }
            return null;
        }
        if (filled < cols.length) {
            for (String col : cols) {
                if (productText(rows, col).isEmpty()) {
                    add(first, err(col, "Give the package length, width, height and weight"));
                    break;
                }
            }
            return null;
        }
        if (bad) {
            return null;
        }
        PackageDimensions p = new PackageDimensions(v[0], v[1], v[2], v[3]);
        if (p.problem() != null) {
            add(first, err(ImportColumns.LENGTH, p.problem()));
            return null;
        }
        return p;
    }

    // ------------------------------------------------------------------ variant-level values

    private Map<String, String> options(SheetRow r, ProductType type, VariationTheme theme) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> generic = ctx.fixedType() == null ? pairs(r, ImportColumns.OPTIONS) : Map.of();
        if (theme == null) {
            boolean any = ctx.fixedType() == null ? !generic.isEmpty()
                    : ImportColumns.dimensions(type).stream().anyMatch(d -> !text(r, optionColumn(d.name())).isEmpty());
            if (any) {
                String col = ctx.fixedType() == null ? ImportColumns.OPTIONS
                        : optionColumn(ImportColumns.dimensions(type).getFirst().name());
                add(r, RowMessage.warning(col, idx(col), "Ignored: set the Variation column to use options"));
            }
            return out;
        }
        for (VariationDimension d : theme.dimensions()) {
            String col = ctx.fixedType() == null ? ImportColumns.OPTIONS : optionColumn(d.name());
            String raw = ctx.fixedType() == null ? generic.getOrDefault(d.name().toLowerCase(Locale.ROOT), "")
                    : text(r, col);
            if (raw.isEmpty()) {
                add(r, err(col, d.name() + " is required for " + theme.name()));
                continue;
            }
            Optional<String> value = d.options().stream().filter(o -> o.equalsIgnoreCase(raw)).findFirst();
            if (value.isEmpty()) {
                add(r, err(col, d.name() + ": choose one of " + d.options()));
            } else {
                out.put(d.name(), value.get());
            }
        }
        if (ctx.fixedType() != null) {
            for (VariationDimension d : ImportColumns.dimensions(type)) {
                if (theme.dimension(d.name()) == null && !text(r, optionColumn(d.name())).isEmpty()) {
                    add(r, RowMessage.warning(optionColumn(d.name()), idx(optionColumn(d.name())),
                            "Ignored: " + theme.name() + " does not use " + d.name()));
                }
            }
        }
        return out;
    }

    private ImportedVariant variant(SheetRow r, String sku, Map<String, String> options, ProductType type,
                                    FulfilmentMode fulfilment, boolean create) {
        Long price = null;
        String p = text(r, ImportColumns.PRICE);
        if (p.isEmpty()) {
            if (create) {
                add(r, err(ImportColumns.PRICE, "Required"));
            }
        } else {
            price = money(p);
            if (price == null || price <= 0 || price > MAX_PRICE_MINOR) {
                add(r, err(ImportColumns.PRICE, "A price above zero in US dollars, e.g. 24.99"));
                price = null;
            }
        }
        Long compareAt = null;
        String c = text(r, ImportColumns.COMPARE_AT);
        if (!c.isEmpty()) {
            compareAt = money(c);
            if (compareAt == null || compareAt <= 0 || compareAt > MAX_PRICE_MINOR) {
                add(r, err(ImportColumns.COMPARE_AT, "A price in US dollars, e.g. 39.99"));
                compareAt = null;
            } else if (price != null && compareAt <= price) {
                add(r, RowMessage.warning(ImportColumns.COMPARE_AT, idx(ImportColumns.COMPARE_AT),
                        "Ignored: the compare-at price must be higher than the price"));
                compareAt = null;
            }
        }
        Integer quantity = null;
        String q = text(r, ImportColumns.QUANTITY);
        if (q.isEmpty()) {
            if (create && fulfilment != FulfilmentMode.AFROTRANSACT) {
                add(r, err(ImportColumns.QUANTITY, "Required"));
            } else if (create) {
                quantity = 0;
            }
        } else {
            quantity = integer(q);
            if (quantity == null || quantity < 0 || quantity > ProductDraftPolicy.MAX_QUANTITY) {
                add(r, err(ImportColumns.QUANTITY, "A whole number from 0 to 100,000"));
                quantity = null;
            }
        }
        String gtin = "";
        boolean exempt = false;
        if (create) {
            String rawGtin = text(r, ImportColumns.GTIN);
            String rawExempt = text(r, ImportColumns.GTIN_EXEMPT);
            Boolean ex = rawExempt.isEmpty() ? Boolean.FALSE : bool(rawExempt);
            if (ex == null) {
                add(r, err(ImportColumns.GTIN_EXEMPT, "Yes or No"));
                ex = false;
            }
            gtin = Gtin.digits(rawGtin);
            if (type.identifierRule() == IdentifierRule.ISBN) {
                if (!Gtin.isValid(gtin) || gtin.length() != 13 || !(gtin.startsWith("978") || gtin.startsWith("979"))) {
                    add(r, err(ImportColumns.GTIN, gtin.isEmpty() ? "ISBN-13 required for books"
                            : "Not a valid ISBN-13 (978/979 prefix, check digit)"));
                }
                if (ex) {
                    add(r, RowMessage.warning(ImportColumns.GTIN_EXEMPT, idx(ImportColumns.GTIN_EXEMPT),
                            "Ignored: books always need an ISBN"));
                }
            } else if (!gtin.isEmpty()) {
                if (!rawGtin.strip().matches("[\\d\\s-]+")) {
                    add(r, err(ImportColumns.GTIN, "Digits only"));
                } else if (!Gtin.isValid(gtin)) {
                    String fix = Gtin.suggestCorrection(gtin);
                    add(r, err(ImportColumns.GTIN, fix == null ? "Not a valid barcode length (8, 12, 13 or 14 digits)"
                            : "Invalid check digit; did you mean " + fix + "?"));
                }
                if (ex) {
                    add(r, RowMessage.warning(ImportColumns.GTIN_EXEMPT, idx(ImportColumns.GTIN_EXEMPT),
                            "Ignored: the row has a barcode"));
                }
            } else if (ex) {
                exempt = true;
            } else {
                add(r, err(ImportColumns.GTIN, "Required, or answer Yes in \"No barcode\""));
            }
        }
        return new ImportedVariant(r.rowNumber(), sku, options, gtin, exempt, price, compareAt, quantity);
    }

    private void checkGtinsAcrossFile(Iterable<ImportedProduct> products) {
        Map<String, Integer> seen = new HashMap<>();
        for (ImportedProduct p : products) {
            for (ImportedVariant v : p.variants()) {
                if (v.gtin() == null || v.gtin().isEmpty() || v.gtinExempt() || !Gtin.isValid(v.gtin())) {
                    continue;
                }
                List<RowMessage> t = messages.get(v.rowNumber());
                if (ctx.existingGtins().contains(v.gtin())) {
                    t.add(err(ImportColumns.GTIN, "This barcode already has a product page; add an offer to it from "
                            + "Listings instead of creating a duplicate"));
                }
                Integer first = seen.putIfAbsent(v.gtin(), v.rowNumber());
                if (first != null) {
                    t.add(err(ImportColumns.GTIN, "Duplicate barcode: also on row " + first));
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void warnIfDifferent(List<SheetRow> rows, String column) {
        if (rows.size() < 2 || !index.containsKey(column)) {
            return;
        }
        String first = null;
        int firstRow = 0;
        for (SheetRow r : rows) {
            String v = text(r, column);
            if (v.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = v;
                firstRow = r.rowNumber();
            } else if (!first.equals(v)) {
                add(r, RowMessage.warning(column, idx(column), "Ignored: a product's " + label(column)
                        + " is taken from row " + firstRow));
            }
        }
    }

    /** A product-level value: the first non-blank cell of the product's rows. */
    private String productText(List<SheetRow> rows, String column) {
        for (SheetRow r : rows) {
            String v = text(r, column);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private String text(SheetRow r, String column) {
        Integer i = index.get(column);
        return i == null ? "" : r.cell(i).strip();
    }

    /** "key: value; key: value" (also "key=value") with lower-case keys. */
    private Map<String, String> pairs(SheetRow r, String column) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = text(r, column);
        if (raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split("[;\\n]")) {
            int sep = part.indexOf(':') >= 0 ? part.indexOf(':') : part.indexOf('=');
            if (sep <= 0) {
                if (!part.isBlank()) {
                    add(r, err(column, "Write each value as name: value, separated by \";\""));
                }
                continue;
            }
            out.put(part.substring(0, sep).strip().toLowerCase(Locale.ROOT), part.substring(sep + 1).strip());
        }
        return out;
    }

    private String optionColumn(String dimension) {
        return ImportColumns.OPTION_PREFIX + dimension;
    }

    private String label(String column) {
        return columns.stream().filter(c -> c.key().equals(column)).map(c -> c.label().toLowerCase(Locale.ROOT))
                .findFirst().orElse(column);
    }

    private String keyAt(int i) {
        return index.entrySet().stream().filter(e -> e.getValue() == i).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private int idx(String column) {
        return index.getOrDefault(column, -1);
    }

    private RowMessage err(String column, String message) {
        return RowMessage.error(column, idx(column), message);
    }

    private void add(SheetRow r, RowMessage m) {
        messages.get(r.rowNumber()).add(m);
    }

    private boolean hasError(SheetRow r) {
        return messages.get(r.rowNumber()).stream().anyMatch(m -> m.severity() == Severity.ERROR);
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "…";
    }

    /** Dollars ("24.99", "$1,299", "24,99") in cents; null when not a price. */
    static Long money(String raw) {
        String s = raw.strip().replace("USD", "").strip();
        Matcher m = MONEY.matcher(s);
        if (m.matches()) {
            long dollars = Long.parseLong(m.group(1).replace(",", ""));
            String cents = m.group(2) == null ? "0" : (m.group(2) + "0").substring(0, 2);
            return dollars * 100 + Long.parseLong(cents);
        }
        m = DECIMAL_COMMA.matcher(s);
        if (m.matches()) {
            return Long.parseLong(m.group(1)) * 100 + Long.parseLong((m.group(2) + "0").substring(0, 2));
        }
        return null;
    }

    static Integer integer(String raw) {
        String s = raw.strip().replace(",", "");
        if (!INTEGER.matcher(s).matches() || s.length() > 9) {
            return null;
        }
        return Integer.parseInt(s.contains(".") ? s.substring(0, s.indexOf('.')) : s);
    }

    static BigDecimal decimal(String raw) {
        try {
            return new BigDecimal(raw.strip().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Boolean bool(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (TRUE.contains(s)) {
            return Boolean.TRUE;
        }
        return FALSE.contains(s) ? Boolean.FALSE : null;
    }

    private static <E extends Enum<E>> E lookup(Map<String, E> labels, String raw, Class<E> type) {
        for (Map.Entry<String, E> e : labels.entrySet()) {
            if (e.getKey().equalsIgnoreCase(raw.strip()) || e.getKey().replace(" - ", " – ").equalsIgnoreCase(raw.strip())) {
                return e.getValue();
            }
        }
        String code = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replaceAll("_+", "_");
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(code)) {
                return e;
            }
        }
        return null;
    }

    /** The product id of an Update-mode group key. */
    public static UUID productIdOf(String groupKey) {
        return UUID.fromString(groupKey);
    }
}
