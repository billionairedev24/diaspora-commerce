package com.afrotransact.catalog.domain.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.ItemCondition;

/**
 * Validates the rows of an offer upload (mode OFFER, S393). One row is one offer on a page that already exists: the match
 * step (barcode, ATIN, or brand with part number) has been done by the caller and arrives as {@link OfferTarget}s. A row
 * never fails because its page exists; it fails when the page cannot be found, the terms are wrong, or the same offer is
 * already earlier in the file (the first one wins). Content columns that a sheet still carries are ignored with a warning.
 */
public final class OfferImportValidator {

    private static final Pattern ATIN = Pattern.compile("AT[0-9A-Z]{8}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Za-z]{2}");
    private static final long MAX_PRICE_MINOR = 100_000_000L;
    private static final int MAX_QUANTITY = 99_999;
    private static final int NOTE_MAX = 200;
    private static final String CONTENT_WARNING =
            "This product is already in the catalog, so we used the existing photos and details.";

    private final ImportMode mode = ImportMode.OFFER;
    private final OfferImportContext ctx;
    private Map<String, Integer> index;
    private final Map<Integer, List<RowMessage>> messages = new HashMap<>();
    private List<String> header;

    private OfferImportValidator(OfferImportContext ctx) {
        this.ctx = ctx;
    }

    /** Which catalog keys the rows of a sheet name, so the caller can match them before validating. */
    public static List<TargetKey> keysOf(ParsedSheet sheet) {
        Map<String, Integer> index = ImportColumns.resolve(sheet.header(), OfferImportColumns.readable(ImportMode.OFFER));
        List<TargetKey> out = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            TargetKey key = keyOf(index, r);
            if (key.present()) {
                out.add(key);
            }
        }
        return out;
    }

    public static OfferImportResult validate(ParsedSheet sheet, OfferImportContext ctx) {
        return new OfferImportValidator(ctx).run(sheet);
    }

    private OfferImportResult run(ParsedSheet sheet) {
        header = sheet.header();
        index = ImportColumns.resolve(header, OfferImportColumns.readable(mode));
        List<String> fileWarnings = new ArrayList<>();
        if (sheet.rows().isEmpty()) {
            throw new SpreadsheetRejected("The file has no offer rows under the header");
        }
        if (sheet.rows().size() > ctx.maxRows()) {
            throw new SpreadsheetRejected("The file has " + sheet.rows().size() + " rows; upload at most "
                    + ctx.maxRows() + " rows per file");
        }
        if (!index.containsKey(ImportColumns.PRICE)) {
            throw new SpreadsheetRejected("The file has no Price column. Download the offer template and keep its header "
                    + "row");
        }
        boolean namesAPage = index.containsKey(ImportColumns.GTIN) || index.containsKey(ImportColumns.ATIN)
                || index.containsKey(OfferImportColumns.NAME);
        if (!namesAPage) {
            throw new SpreadsheetRejected("The file needs a Product name, Barcode or ATIN column to say which product "
                    + "each row sells");
        }
        List<String> ignored = ignoredColumns(header);
        if (!ignored.isEmpty()) {
            fileWarnings.add("These columns are not used in an offer upload: " + String.join(", ", ignored)
                    + ". The product page already has its photos, title and details.");
        }
        sheet.rows().forEach(r -> messages.put(r.rowNumber(), new ArrayList<>()));
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<OfferRow> rows = new ArrayList<>();
        List<ValidatedRow> validated = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            OfferRow parsed = row(r, seen);
            if (parsed != null) {
                rows.add(parsed);
            }
        }
        for (SheetRow r : sheet.rows()) {
            List<RowMessage> ms = messages.get(r.rowNumber());
            RowStatus status = ms.stream().anyMatch(m -> m.severity() == Severity.ERROR) ? RowStatus.ERROR
                    : ms.isEmpty() ? RowStatus.VALID : RowStatus.WARNING;
            OfferRow parsed = rows.stream().filter(x -> x.rowNumber() == r.rowNumber()).findFirst().orElse(null);
            validated.add(new ValidatedRow(r.rowNumber(), "r" + r.rowNumber(), parsed == null ? givenSku(r) : parsed.sku(),
                    status, ms, r.cells()));
        }
        List<OfferRow> importable = rows.stream().filter(x -> validated.stream()
                .anyMatch(v -> v.rowNumber() == x.rowNumber() && v.status() != RowStatus.ERROR)).toList();
        return new OfferImportResult(new ValidationResult(header, fileWarnings, validated, List.of(), validated.size()),
                importable);
    }

    private OfferRow row(SheetRow r, Map<String, Integer> seen) {
        TargetKey key = keyOf(index, r);
        if (!key.present()) {
            add(r, RowMessage.error(OfferImportColumns.NAME, idx(OfferImportColumns.NAME), "Say which product this row "
                    + "sells: type its name (and its size), or give its barcode"));
            return null;
        }
        if (key.gtin() != null && !Gtin.isValid(key.gtin())) {
            String fix = Gtin.suggestCorrection(key.gtin());
            add(r, RowMessage.error(ImportColumns.GTIN, idx(ImportColumns.GTIN), "That barcode does not look right"
                    + (fix == null ? "" : ". Did you mean " + fix + "?")));
            return null;
        }
        if (key.gtin() == null && key.atin() != null && !ATIN.matcher(key.atin()).matches()) {
            add(r, RowMessage.error(ImportColumns.ATIN, idx(ImportColumns.ATIN), "That ATIN does not look right. It is AT "
                    + "followed by 8 letters or digits"));
            return null;
        }
        OfferTarget target = ctx.targets().get(key.id());
        if (target == null) {
            add(r, RowMessage.error(null, -1, "We could not look this product up. Upload the file again in a few minutes"));
            return null;
        }
        String column = key.gtin() != null ? ImportColumns.GTIN : key.atin() != null ? ImportColumns.ATIN
                : OfferImportColumns.NAME;
        switch (target.kind()) {
            case NO_MATCH -> {
                add(r, RowMessage.error(column, idx(column), target.message() != null ? target.message()
                        : "We could not find this product on AfroTransact. Add it with the products template."));
                return null;
            }
            case INVALID -> {
                add(r, RowMessage.error(column, idx(column), target.message()));
                return null;
            }
            case AMBIGUOUS -> {
                add(r, RowMessage.error(column, idx(column), target.message()));
                return null;
            }
            default -> {
            }
        }
        if (target.refusal() != null) {
            add(r, RowMessage.error(column, idx(column), target.refusal().message()));
            return null;
        }
        boolean held = target.held();
        if (held) {
            add(r, RowMessage.warning(column, idx(column), heldWords(target)));
        }
        if (contentGiven(r)) {
            add(r, RowMessage.warning(null, -1, CONTENT_WARNING));
        }

        ItemCondition condition = condition(r);
        FulfilmentMode fulfilment = fulfilment(r);
        Long price = money(r, ImportColumns.PRICE, true);
        Long list = money(r, OfferImportColumns.LIST_PRICE, false);
        Long shipping = money(r, OfferImportColumns.SHIPPING, false);
        Integer quantity = integer(r, ImportColumns.QUANTITY, 0, MAX_QUANTITY);
        Integer handling = integer(r, ImportColumns.HANDLING, 0, 30);
        if (price != null && list != null && list != 0 && list < price) {
            add(r, RowMessage.error(OfferImportColumns.LIST_PRICE, idx(OfferImportColumns.LIST_PRICE),
                    "The list price must be at least the price"));
        }
        if (fulfilment == FulfilmentMode.SELLER && cell(r, ImportColumns.QUANTITY).isEmpty()) {
            add(r, RowMessage.error(ImportColumns.QUANTITY, idx(ImportColumns.QUANTITY),
                    "Give the quantity for an item you ship yourself"));
        }
        if (fulfilment == FulfilmentMode.AFROTRANSACT && quantity != null) {
            add(r, RowMessage.warning(ImportColumns.QUANTITY, idx(ImportColumns.QUANTITY), "Stock of items Fulfilled by "
                    + "AfroTransact comes from the fulfilment centre, so the quantity is ignored"));
            quantity = null;
        }
        String note = cell(r, OfferImportColumns.CONDITION_NOTE);
        if (condition != null && condition != ItemCondition.NEW && note.isEmpty()) {
            add(r, RowMessage.error(OfferImportColumns.CONDITION_NOTE, idx(OfferImportColumns.CONDITION_NOTE),
                    "Say what is different about this item"));
        }
        if (note.length() > NOTE_MAX) {
            add(r, RowMessage.error(OfferImportColumns.CONDITION_NOTE, idx(OfferImportColumns.CONDITION_NOTE),
                    "At most " + NOTE_MAX + " characters"));
        }
        OfferRow.Box box = box(r, fulfilment);
        String country = cell(r, OfferImportColumns.SHIP_COUNTRY);
        if (!country.isEmpty() && !COUNTRY.matcher(country).matches()) {
            add(r, RowMessage.error(OfferImportColumns.SHIP_COUNTRY, idx(OfferImportColumns.SHIP_COUNTRY),
                    "Use a two-letter country code, for example US"));
        }
        if (hasError(r) || condition == null || fulfilment == null || price == null) {
            return null;
        }
        String sku = sku(r, index, target, condition, fulfilment);
        String channel = fulfilment == FulfilmentMode.AFROTRANSACT ? "FBA" : "SELLER";
        String duplicateKey = target.atin() + "|" + condition + "|" + channel;
        Integer first = seen.putIfAbsent(duplicateKey, r.rowNumber());
        if (first != null) {
            add(r, RowMessage.error(column, idx(column), (key.gtin() != null ? "This barcode" : "This product")
                    + " appears twice in your file. Row " + first + " is used and this one is left out"));
            return null;
        }
        return new OfferRow(r.rowNumber(), sku, target.atin(), target.parentAtin(), condition, price, list, shipping,
                fulfilment, quantity, handling, cell(r, OfferImportColumns.SHIP_CITY),
                cell(r, OfferImportColumns.SHIP_REGION), country.isEmpty() ? "US" : country.toUpperCase(Locale.ROOT),
                cell(r, OfferImportColumns.SHIP_ZIP), note.isEmpty() ? null : note, box, held,
                held ? heldWords(target) : null);
    }

    private static String heldWords(OfferTarget target) {
        OfferTarget.Blocker b = target.blockers().getFirst();
        return "offers.selling-approval-required".equals(b.code())
                ? b.message() + " This row is held and is not for sale."
                : "This product page is not on sale yet, so this row is held. Upload it again when the page is on sale.";
    }

    /** The row's seller SKU: the one given, else one made from the ATIN, the condition and the channel. */
    public static String sku(SheetRow r, Map<String, Integer> index, OfferTarget target, ItemCondition condition,
                             FulfilmentMode fulfilment) {
        Integer col = index.get(OfferImportColumns.SELLER_SKU);
        String given = col == null ? "" : r.cell(col).strip().toUpperCase(Locale.ROOT);
        if (!given.isEmpty()) {
            return given.length() > 40 ? given.substring(0, 40) : given;
        }
        if (target == null || target.atin() == null) {
            return "";
        }
        return generatedSku(target.atin(), condition, fulfilment);
    }

    /** The same offer always gets the same SKU, so a file uploaded again finds its offers. */
    public static String generatedSku(String atin, ItemCondition condition, FulfilmentMode fulfilment) {
        return atin + (condition == null || condition == ItemCondition.NEW ? "" : "-" + condition.name())
                + (fulfilment == FulfilmentMode.AFROTRANSACT ? "-FBA" : "");
    }

    private String givenSku(SheetRow r) {
        String given = cell(r, OfferImportColumns.SELLER_SKU).toUpperCase(Locale.ROOT);
        return given.length() > 40 ? given.substring(0, 40) : given;
    }

    private ItemCondition condition(SheetRow r) {
        String raw = cell(r, ImportColumns.CONDITION);
        if (raw.isEmpty()) {
            return ItemCondition.NEW;
        }
        ItemCondition c = labelled(ImportColumns.CONDITIONS, raw, ItemCondition.class);
        if (c == null) {
            add(r, RowMessage.error(ImportColumns.CONDITION, idx(ImportColumns.CONDITION), "Use one of: "
                    + String.join(", ", ImportColumns.conditionLabels())));
        }
        return c;
    }

    private FulfilmentMode fulfilment(SheetRow r) {
        String raw = cell(r, ImportColumns.FULFILMENT);
        if (raw.isEmpty()) {
            return FulfilmentMode.SELLER;
        }
        FulfilmentMode f = labelled(ImportColumns.FULFILMENTS, raw, FulfilmentMode.class);
        if (f == null) {
            add(r, RowMessage.error(ImportColumns.FULFILMENT, idx(ImportColumns.FULFILMENT),
                    "Use Ships from you or Fulfilled by AfroTransact"));
        }
        return f;
    }

    private static <E extends Enum<E>> E labelled(Map<String, E> labels, String raw, Class<E> type) {
        String wanted = raw.strip();
        for (Map.Entry<String, E> e : labels.entrySet()) {
            if (e.getKey().equalsIgnoreCase(wanted) || e.getKey().replace(" - ", " – ").equalsIgnoreCase(wanted)) {
                return e.getValue();
            }
        }
        String code = wanted.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replaceAll("_+", "_");
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(code)) {
                return e;
            }
        }
        return null;
    }

    private Long money(SheetRow r, String column, boolean required) {
        String raw = cell(r, column);
        if (raw.isEmpty()) {
            if (required) {
                add(r, RowMessage.error(column, idx(column), "Required"));
            }
            return null;
        }
        Long minor = ImportValidator.money(raw);
        if (minor == null) {
            add(r, RowMessage.error(column, idx(column), "Not a price. Use dollars, for example 24.99"));
            return null;
        }
        if (minor > MAX_PRICE_MINOR || (required && minor <= 0)) {
            add(r, RowMessage.error(column, idx(column), required ? "The price must be above zero and below "
                    + MAX_PRICE_MINOR / 100 : "That amount is too large"));
            return null;
        }
        return minor;
    }

    private Integer integer(SheetRow r, String column, int min, int max) {
        String raw = cell(r, column);
        if (raw.isEmpty()) {
            return null;
        }
        Integer v = ImportValidator.integer(raw);
        if (v == null || v < min || v > max) {
            add(r, RowMessage.error(column, idx(column), "A whole number from " + min + " to " + max));
            return null;
        }
        return v;
    }

    private OfferRow.Box box(SheetRow r, FulfilmentMode fulfilment) {
        String[] columns = {ImportColumns.LENGTH, ImportColumns.WIDTH, ImportColumns.HEIGHT, ImportColumns.WEIGHT};
        double[] values = new double[4];
        int given = 0;
        for (int i = 0; i < columns.length; i++) {
            String raw = cell(r, columns[i]);
            if (raw.isEmpty()) {
                continue;
            }
            given++;
            BigDecimal d = ImportValidator.decimal(raw);
            if (d == null || d.signum() <= 0) {
                add(r, RowMessage.error(columns[i], idx(columns[i]), "A number above zero"));
                return null;
            }
            values[i] = d.doubleValue();
        }
        if (given == 0) {
            return null;
        }
        if (given < 4) {
            add(r, RowMessage.error(ImportColumns.LENGTH, idx(ImportColumns.LENGTH),
                    "Give the length, width, height and weight together"));
            return null;
        }
        if (fulfilment != FulfilmentMode.AFROTRANSACT) {
            add(r, RowMessage.warning(ImportColumns.LENGTH, idx(ImportColumns.LENGTH), "The package is only for Fulfilled by "
                    + "AfroTransact items, so it is ignored"));
            return null;
        }
        return new OfferRow.Box(values[0], values[1], values[2], values[3]);
    }

    private boolean contentGiven(SheetRow r) {
        for (int i = 0; i < header.size(); i++) {
            if (isContentColumn(header.get(i)) && !lookupColumn(i) && !r.cell(i).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<String> ignoredColumns(List<String> header) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            if (isContentColumn(header.get(i)) && !lookupColumn(i)) {
                out.add(header.get(i).strip());
            }
        }
        return out;
    }

    /** The category column of the offer template finds the product; it is not page content. */
    private boolean lookupColumn(int column) {
        Integer category = index.get(OfferImportColumns.CATEGORY_HINT);
        return category != null && category == column;
    }

    private static boolean isContentColumn(String header) {
        String h = ImportColumns.normalise(header);
        return h.equals("title") || h.equals("description") || h.equals("category") || h.equals("attributes")
                || h.contains("image") || h.contains("photo") || h.equals("keywords") || h.equals("variation")
                || h.equals("parent sku");
    }

    private static TargetKey keyOf(Map<String, Integer> index, SheetRow r) {
        return TargetKey.of(at(index, r, ImportColumns.GTIN), at(index, r, ImportColumns.ATIN),
                at(index, r, OfferImportColumns.NAME), at(index, r, ImportColumns.BRAND),
                at(index, r, OfferImportColumns.SIZE), at(index, r, OfferImportColumns.CATEGORY_HINT));
    }

    private static String at(Map<String, Integer> index, SheetRow r, String column) {
        Integer i = index.get(column);
        return i == null ? "" : r.cell(i).strip();
    }

    private String cell(SheetRow r, String column) {
        return at(index, r, column);
    }

    private int idx(String column) {
        Integer i = index.get(column);
        return i == null ? -1 : i;
    }

    private void add(SheetRow r, RowMessage m) {
        messages.get(r.rowNumber()).add(m);
    }

    private boolean hasError(SheetRow r) {
        return messages.get(r.rowNumber()).stream().anyMatch(m -> m.severity() == Severity.ERROR);
    }
}
