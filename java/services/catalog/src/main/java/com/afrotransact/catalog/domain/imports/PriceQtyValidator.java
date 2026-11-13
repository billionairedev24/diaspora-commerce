package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.Gtin;

/**
 * Validates a price and quantity feed (mode PRICE_QTY, S394): one row per offer, with a new price, list price and quantity.
 * A row names its offer by Seller SKU first; the ATIN is accepted, and a barcode only when the row has neither (offers-service
 * then accepts it only when it matches exactly one of the seller's own listings, else the row fails and says to use the SKU).
 * A blank cell changes nothing. Whether the offer is the organisation's own is decided by offers-service when the row is
 * applied (another seller's SKU looks like a missing one).
 */
public final class PriceQtyValidator {

    private static final Pattern ATIN = Pattern.compile("AT[0-9A-Z]{8}");
    private static final long MAX_PRICE_MINOR = 100_000_000L;
    private static final int MAX_QUANTITY = 99_999;

    private final Map<String, Integer> index;
    private final Map<Integer, List<RowMessage>> messages = new HashMap<>();

    private PriceQtyValidator(Map<String, Integer> index) {
        this.index = index;
    }

    public static PriceQtyResult validate(ParsedSheet sheet, int maxRows) {
        Map<String, Integer> index = ImportColumns.resolve(sheet.header(), OfferImportColumns.readable(ImportMode.PRICE_QTY));
        return new PriceQtyValidator(index).run(sheet, maxRows);
    }

    private PriceQtyResult run(ParsedSheet sheet, int maxRows) {
        if (sheet.rows().isEmpty()) {
            throw new SpreadsheetRejected("The file has no rows under the header");
        }
        if (sheet.rows().size() > maxRows) {
            throw new SpreadsheetRejected("The file has " + sheet.rows().size() + " rows; upload at most " + maxRows
                    + " rows per file");
        }
        if (!index.containsKey(OfferImportColumns.SELLER_SKU) && !index.containsKey(ImportColumns.ATIN)
                && !index.containsKey(ImportColumns.GTIN)) {
            throw new SpreadsheetRejected("The file needs a Seller SKU column (or ATIN, or Barcode) to say which listing "
                    + "each row changes");
        }
        if (!index.containsKey(ImportColumns.PRICE) && !index.containsKey(OfferImportColumns.LIST_PRICE)
                && !index.containsKey(ImportColumns.QUANTITY)) {
            throw new SpreadsheetRejected("The file needs a Price, List price or Quantity column. Download the template and "
                    + "keep its header row");
        }
        List<String> fileWarnings = new ArrayList<>();
        sheet.rows().forEach(r -> messages.put(r.rowNumber(), new ArrayList<>()));
        Map<String, Integer> seen = new HashMap<>();
        List<PriceQtyRow> rows = new ArrayList<>();
        Map<Integer, PriceQtyRow> byRow = new HashMap<>();
        for (SheetRow r : sheet.rows()) {
            PriceQtyRow parsed = row(r, seen);
            if (parsed != null) {
                byRow.put(r.rowNumber(), parsed);
            }
        }
        List<ValidatedRow> validated = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            List<RowMessage> ms = messages.get(r.rowNumber());
            RowStatus status = ms.stream().anyMatch(m -> m.severity() == Severity.ERROR) ? RowStatus.ERROR
                    : ms.isEmpty() ? RowStatus.VALID : RowStatus.WARNING;
            PriceQtyRow parsed = byRow.get(r.rowNumber());
            if (parsed != null && status != RowStatus.ERROR) {
                rows.add(parsed);
            }
            String sku = cell(r, OfferImportColumns.SELLER_SKU).toUpperCase(Locale.ROOT);
            validated.add(new ValidatedRow(r.rowNumber(), "r" + r.rowNumber(), sku.length() > 60 ? sku.substring(0, 60) : sku,
                    status, ms, r.cells()));
        }
        return new PriceQtyResult(new ValidationResult(sheet.header(), fileWarnings, validated, List.of(),
                validated.size()), rows);
    }

    private PriceQtyRow row(SheetRow r, Map<String, Integer> seen) {
        String sku = cell(r, OfferImportColumns.SELLER_SKU).toUpperCase(Locale.ROOT);
        String atin = cell(r, ImportColumns.ATIN).toUpperCase(Locale.ROOT);
        String gtin = Gtin.digits(cell(r, ImportColumns.GTIN));
        if (sku.isEmpty() && atin.isEmpty() && gtin.isEmpty()) {
            add(r, RowMessage.error(OfferImportColumns.SELLER_SKU, idx(OfferImportColumns.SELLER_SKU),
                    "Give the seller SKU or the ATIN"));
            return null;
        }
        if (sku.isEmpty() && atin.isEmpty() && !Gtin.isValid(gtin)) {
            add(r, RowMessage.error(ImportColumns.GTIN, idx(ImportColumns.GTIN), "That barcode does not look right. "
                    + "Use your SKU instead"));
            return null;
        }
        if (!atin.isEmpty() && !ATIN.matcher(atin).matches()) {
            add(r, RowMessage.error(ImportColumns.ATIN, idx(ImportColumns.ATIN), "That ATIN does not look right. It is AT "
                    + "followed by 8 letters or digits"));
            return null;
        }
        if (sku.length() > 40) {
            add(r, RowMessage.error(OfferImportColumns.SELLER_SKU, idx(OfferImportColumns.SELLER_SKU),
                    "At most 40 characters"));
            return null;
        }
        Long price = money(r, ImportColumns.PRICE, true);
        Long list = money(r, OfferImportColumns.LIST_PRICE, false);
        Integer quantity = quantity(r);
        if (price != null && list != null && list != 0 && list < price) {
            add(r, RowMessage.error(OfferImportColumns.LIST_PRICE, idx(OfferImportColumns.LIST_PRICE),
                    "The list price must be at least the price"));
        }
        if (hasError(r)) {
            return null;
        }
        if (price == null && list == null && quantity == null) {
            add(r, RowMessage.warning(null, -1, "Nothing to change on this row: the price, list price and quantity are "
                    + "blank"));
        }
        boolean byGtin = sku.isEmpty() && atin.isEmpty();
        String key = !sku.isEmpty() ? "sku:" + sku : !atin.isEmpty() ? "atin:" + atin : "gtin:" + padded(gtin);
        Integer first = seen.putIfAbsent(key, r.rowNumber());
        if (first != null) {
            String column = !sku.isEmpty() ? OfferImportColumns.SELLER_SKU : !atin.isEmpty() ? ImportColumns.ATIN
                    : ImportColumns.GTIN;
            add(r, RowMessage.error(column, idx(column), "This " + (!sku.isEmpty() ? "SKU" : !atin.isEmpty() ? "ATIN"
                    : "barcode") + " appears twice in your file. Row " + first + " is used and this one is left out"));
            return null;
        }
        return new PriceQtyRow(r.rowNumber(), sku.isEmpty() ? null : sku, atin.isEmpty() ? null : atin, price, list,
                quantity, byGtin ? gtin : null);
    }

    /** The barcode as 14 digits, so a UPC-A and its GTIN-14 form count as the same barcode. */
    private static String padded(String digits) {
        return digits.length() >= 14 ? digits : "0".repeat(14 - digits.length()) + digits;
    }

    private Long money(SheetRow r, String column, boolean positive) {
        String raw = cell(r, column);
        if (raw.isEmpty()) {
            return null;
        }
        Long minor = ImportValidator.money(raw);
        if (minor == null) {
            add(r, RowMessage.error(column, idx(column), "Not a price. Use dollars, for example 24.99"));
            return null;
        }
        if (minor > MAX_PRICE_MINOR || (positive && minor <= 0)) {
            add(r, RowMessage.error(column, idx(column), positive ? "The price must be above zero and below "
                    + MAX_PRICE_MINOR / 100 : "That amount is too large"));
            return null;
        }
        return minor;
    }

    private Integer quantity(SheetRow r) {
        String raw = cell(r, ImportColumns.QUANTITY);
        if (raw.isEmpty()) {
            return null;
        }
        Integer v = ImportValidator.integer(raw);
        if (v == null || v < 0 || v > MAX_QUANTITY) {
            add(r, RowMessage.error(ImportColumns.QUANTITY, idx(ImportColumns.QUANTITY),
                    "A whole number from 0 to " + MAX_QUANTITY));
            return null;
        }
        return v;
    }

    private String cell(SheetRow r, String column) {
        Integer i = index.get(column);
        return i == null ? "" : r.cell(i).strip();
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
