package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the records of a stock file (S395) before anything is stored: header, SKU, location, quantity, lot and expiry.
 * Whether the seller holds the SKU at the location is answered by the caller ({@code held}, keyed {@code SKU|NODE}); a SKU
 * of another organisation looks exactly like an unknown one. Row problems are plain messages on the row; only a file that
 * cannot be read at all is refused.
 */
public final class StockImportValidator {

    /** Largest quantity on hand a file may set (or reach with a change). */
    public static final int MAX_STOCK = 99_999;
    /** Rows in one file. */
    public static final int MAX_ROWS = 50_000;

    public static final String SKU = "seller_sku";
    public static final String LOCATION = "location";
    public static final String QUANTITY = "on_hand";
    public static final String LOT = "lot";
    public static final String EXPIRY = "expiry";

    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d{1,9}");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("seller sku", SKU), Map.entry("sku", SKU), Map.entry("seller_sku", SKU),
            Map.entry("location", LOCATION), Map.entry("node", LOCATION), Map.entry("fulfilment centre", LOCATION),
            Map.entry("on hand", QUANTITY), Map.entry("on_hand", QUANTITY), Map.entry("count", QUANTITY),
            Map.entry("counted", QUANTITY), Map.entry("quantity", QUANTITY), Map.entry("change", QUANTITY),
            Map.entry("delta", QUANTITY), Map.entry("lot", LOT), Map.entry("lot number", LOT),
            Map.entry("lot no", LOT), Map.entry("expiry", EXPIRY), Map.entry("expiry date", EXPIRY),
            Map.entry("expires", EXPIRY));

    private final StockImportMode mode;
    private final Set<String> activeNodes;
    private final Set<String> held;
    private final Map<String, Integer> index = new HashMap<>();

    private StockImportValidator(StockImportMode mode, Set<String> activeNodes, Set<String> held) {
        this.mode = mode;
        this.activeNodes = activeNodes;
        this.held = held;
    }

    /** The result of validating a file. */
    public record Result(List<String> header, List<StockImportRow> rows) {

        public Result {
            header = List.copyOf(header);
            rows = List.copyOf(rows);
        }

        public long count(String status) {
            return rows.stream().filter(r -> r.status().equals(status)).count();
        }
    }

    /** The SKUs the file names, upper case, so the caller can load what the seller holds. */
    public static Set<String> skusOf(List<List<String>> records) {
        Map<String, Integer> idx = resolve(headerOf(records));
        Integer col = idx.get(SKU);
        Set<String> out = new LinkedHashSet<>();
        if (col == null) {
            return out;
        }
        for (List<String> r : dataOf(records)) {
            String sku = cell(r, col).toUpperCase(Locale.ROOT);
            if (!sku.isEmpty()) {
                out.add(sku);
            }
        }
        return out;
    }

    public static Result validate(List<List<String>> records, StockImportMode mode, Set<String> activeNodes,
                                  Set<String> held, int maxRows) {
        return new StockImportValidator(mode, activeNodes, held).run(records, maxRows);
    }

    private Result run(List<List<String>> records, int maxRows) {
        List<String> header = headerOf(records);
        index.putAll(resolve(header));
        List<List<String>> data = dataOf(records);
        if (data.isEmpty()) {
            throw new InventoryRuleViolation("inventory.upload-rejected",
                    "The file has no rows under the header. Download the template and fill it in");
        }
        if (data.size() > maxRows) {
            throw new InventoryRuleViolation("inventory.upload-rejected", "The file has " + data.size()
                    + " rows; upload at most " + maxRows + " rows per file");
        }
        for (String required : List.of(SKU, LOCATION, QUANTITY)) {
            if (!index.containsKey(required)) {
                throw new InventoryRuleViolation("inventory.upload-rejected", "The file has no "
                        + label(required) + " column. Download the template and keep its header row");
            }
        }
        List<StockImportRow> rows = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        int number = headerRowNumber(records);
        for (List<String> r : data) {
            number = nextRowNumber(records, r, number);
            rows.add(row(number, r, seen));
        }
        return new Result(header, rows);
    }

    private StockImportRow row(int number, List<String> cells, Map<String, Integer> seen) {
        List<StockRowMessage> messages = new ArrayList<>();
        String sku = cell(cells, index.get(SKU)).toUpperCase(Locale.ROOT);
        String node = cell(cells, index.get(LOCATION)).toUpperCase(Locale.ROOT);
        String lot = index.containsKey(LOT) ? cell(cells, index.get(LOT)).toUpperCase(Locale.ROOT) : "";
        String rawQty = cell(cells, index.get(QUANTITY));
        String rawExpiry = index.containsKey(EXPIRY) ? cell(cells, index.get(EXPIRY)) : "";
        if (sku.isEmpty()) {
            messages.add(StockRowMessage.error(SKU, "Give the seller SKU"));
        } else if (sku.length() > 60) {
            messages.add(StockRowMessage.error(SKU, "At most 60 characters"));
        }
        if (node.isEmpty()) {
            messages.add(StockRowMessage.error(LOCATION, "Give the location, for example HOU-FC"));
        } else if (!activeNodes.contains(node)) {
            messages.add(StockRowMessage.error(LOCATION, node + " is not a location we know. Use a code from your "
                    + "inventory page, for example HOU-FC"));
        }
        if (!sku.isEmpty() && !node.isEmpty() && activeNodes.contains(node) && !held.contains(sku + "|" + node)) {
            messages.add(StockRowMessage.error(SKU, "We do not hold " + sku + " for you at " + node
                    + ". Check the SKU and the location"));
        }
        Integer qty = quantity(rawQty, messages);
        LocalDate expiry = expiry(rawExpiry, messages);
        if (lot.length() > 60) {
            messages.add(StockRowMessage.error(LOT, "At most 60 characters"));
        }
        if (messages.stream().noneMatch(StockRowMessage::isError)) {
            String key = sku + "|" + node + "|" + lot;
            Integer first = seen.putIfAbsent(key, number);
            if (first != null) {
                messages.add(StockRowMessage.error(SKU, "This SKU, location and lot appear twice in your file. Row "
                        + first + " is used and this one is left out"));
            }
        }
        boolean error = messages.stream().anyMatch(StockRowMessage::isError);
        return StockImportRow.validated(number, sku, node, lot.isEmpty() ? null : lot, error ? null : qty, expiry,
                messages, cells);
    }

    private Integer quantity(String raw, List<StockRowMessage> messages) {
        if (raw.isEmpty()) {
            messages.add(StockRowMessage.error(QUANTITY, mode == StockImportMode.DELTA
                    ? "Give the change, for example 5 or -3" : "Give the quantity on hand"));
            return null;
        }
        if (!WHOLE.matcher(raw).matches()) {
            messages.add(StockRowMessage.error(QUANTITY, "A whole number"));
            return null;
        }
        int value = Integer.parseInt(raw.startsWith("+") ? raw.substring(1) : raw);
        if (mode == StockImportMode.ABSOLUTE && value < 0) {
            messages.add(StockRowMessage.error(QUANTITY, "Stock cannot be negative"));
            return null;
        }
        if (Math.abs(value) > MAX_STOCK) {
            messages.add(StockRowMessage.error(QUANTITY, "At most " + MAX_STOCK + " units"));
            return null;
        }
        return value;
    }

    private static LocalDate expiry(String raw, List<StockRowMessage> messages) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ex) {
            messages.add(StockRowMessage.error(EXPIRY, "Write the date as year-month-day, for example 2027-03-31"));
            return null;
        }
    }

    // ------------------------------------------------------------------ file layout

    private static List<String> headerOf(List<List<String>> records) {
        for (List<String> r : records) {
            if (!blank(r) && !comment(r)) {
                return r;
            }
        }
        throw new InventoryRuleViolation("inventory.upload-rejected",
                "The file is empty: it needs the template's header row and your counts");
    }

    private static List<List<String>> dataOf(List<List<String>> records) {
        List<List<String>> out = new ArrayList<>();
        boolean headerSeen = false;
        for (List<String> r : records) {
            if (blank(r) || comment(r)) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /** The line number of the header, so rows are numbered like the seller's spreadsheet (header = 1 unless comments precede). */
    private static int headerRowNumber(List<List<String>> records) {
        for (int i = 0; i < records.size(); i++) {
            if (!blank(records.get(i)) && !comment(records.get(i))) {
                return i + 1;
            }
        }
        return 1;
    }

    /** The line number of the next data row after {@code previous} (blank and comment lines count as lines). */
    private static int nextRowNumber(List<List<String>> records, List<String> row, int previous) {
        for (int i = previous; i < records.size(); i++) {
            if (records.get(i) == row) {
                return i + 1;
            }
        }
        return previous + 1;
    }

    private static Map<String, Integer> resolve(List<String> header) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = ALIASES.get(header.get(i).strip().replaceAll("\\s*\\*$", "").toLowerCase(Locale.ROOT)
                    .replace('_', ' ').replaceAll("\\s+", " "));
            if (key == null) {
                key = ALIASES.get(header.get(i).strip().toLowerCase(Locale.ROOT));
            }
            if (key != null) {
                out.putIfAbsent(key, i);
            }
        }
        return out;
    }

    private static String cell(List<String> row, Integer col) {
        return col == null || col >= row.size() || row.get(col) == null ? "" : row.get(col).strip();
    }

    private static boolean blank(List<String> row) {
        return row.stream().allMatch(c -> c == null || c.isBlank());
    }

    private static boolean comment(List<String> row) {
        return !row.isEmpty() && row.getFirst() != null && row.getFirst().strip().startsWith("#");
    }

    private static String label(String key) {
        return switch (key) {
            case SKU -> "Seller SKU";
            case LOCATION -> "Location";
            case QUANTITY -> "On hand";
            default -> key;
        };
    }
}
