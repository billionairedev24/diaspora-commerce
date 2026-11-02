package com.afrotransact.catalog.domain.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.CustomValues;
import com.afrotransact.catalog.domain.imports.ChartImportResult.ParsedChart;
import com.afrotransact.catalog.domain.sizing.ChartColumn;
import com.afrotransact.catalog.domain.sizing.ChartContent;
import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementRange;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChartRules;

/**
 * Validates an uploaded size chart sheet row by row (see {@link ChartImportColumns} for the layout) and reads the
 * charts it describes. A chart is importable only when all of its rows are valid: it is saved whole.
 */
public final class ChartImportValidator {

    private static final String NUMBER = "(\\d{1,4}(?:[.,]\\d{1,2})?)";
    private static final Pattern SINGLE = Pattern.compile("^" + NUMBER + "$");
    private static final Pattern RANGE = Pattern.compile("^" + NUMBER + "\\s*(?:-|–|—|to)\\s*" + NUMBER + "$");
    private static final Pattern UNIT_SUFFIX = Pattern.compile("\\s*(?:cm|in|\")$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PARENTHESES = Pattern.compile("\\s*\\([^)]*\\)\\s*$");
    private static final Pattern ROW_FIELD = Pattern.compile("^rows\\[(\\d+)\\]\\.(size|values\\.(.+))$");
    private static final int MAX_CHARTS = 200;

    private final ChartImportContext ctx;
    private Map<String, Integer> fixed;
    private final Map<String, Integer> measurementColumns = new LinkedHashMap<>();
    private final Map<String, String> measurementLabels = new LinkedHashMap<>();
    private final Map<Integer, List<RowMessage>> messages = new LinkedHashMap<>();

    private ChartImportValidator(ChartImportContext ctx) {
        this.ctx = ctx;
    }

    /** Throws {@link SpreadsheetRejected} when the file cannot be read as a size chart upload at all. */
    public static ChartImportResult validate(ParsedSheet sheet, ChartImportContext ctx) {
        return new ChartImportValidator(ctx).run(sheet);
    }

    private ChartImportResult run(ParsedSheet sheet) {
        List<String> warnings = new ArrayList<>();
        readHeader(sheet.header(), warnings);
        if (sheet.rows().isEmpty()) {
            throw new SpreadsheetRejected("The file has no size rows under the header");
        }
        if (sheet.rows().size() > ImportLimits.MAX_ROWS) {
            throw new SpreadsheetRejected("The file has " + sheet.rows().size() + " rows; upload at most "
                    + ImportLimits.MAX_ROWS + " rows per file");
        }
        sheet.rows().forEach(r -> messages.put(r.rowNumber(), new ArrayList<>()));
        Map<String, List<SheetRow>> groups = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (SheetRow r : sheet.rows()) {
            String name = CustomValues.clean(text(r, ChartImportColumns.CHART_NAME));
            if (name.isEmpty()) {
                add(r, error(ChartImportColumns.CHART_NAME, "Required: the name of the size chart"));
                continue;
            }
            groups.computeIfAbsent(CustomValues.key(name), k -> new ArrayList<>()).add(r);
            names.putIfAbsent(CustomValues.key(name), name);
        }
        if (groups.size() > MAX_CHARTS) {
            throw new SpreadsheetRejected("The file has " + groups.size() + " size charts; upload at most " + MAX_CHARTS
                    + " per file");
        }
        List<ParsedChart> charts = new ArrayList<>();
        for (Map.Entry<String, List<SheetRow>> g : groups.entrySet()) {
            ParsedChart chart = chart(names.get(g.getKey()), g.getValue());
            if (chart != null) {
                charts.add(chart);
            }
        }
        List<ValidatedRow> rows = new ArrayList<>();
        for (SheetRow r : sheet.rows()) {
            List<RowMessage> ms = messages.get(r.rowNumber());
            RowStatus status = ms.stream().anyMatch(m -> m.severity() == Severity.ERROR) ? RowStatus.ERROR
                    : ms.isEmpty() ? RowStatus.VALID : RowStatus.WARNING;
            rows.add(new ValidatedRow(r.rowNumber(), CustomValues.clean(text(r, ChartImportColumns.CHART_NAME)),
                    CustomValues.clean(text(r, ChartImportColumns.SIZE)), status, ms, r.cells()));
        }
        return new ChartImportResult(sheet.header(), warnings, rows, charts, groups.size());
    }

    // ------------------------------------------------------------------ header

    private void readHeader(List<String> header, List<String> warnings) {
        if (header.size() > ImportLimits.MAX_COLUMNS) {
            throw new SpreadsheetRejected("The file has " + header.size() + " columns; at most "
                    + ImportLimits.MAX_COLUMNS + " are read");
        }
        List<ImportColumn> known = ChartImportColumns.columns(null);
        fixed = ImportColumns.resolve(header, known);
        List<String> missing = new ArrayList<>();
        for (ImportColumn c : known) {
            if (c.required() && !fixed.containsKey(c.key())) {
                missing.add(c.header());
            }
        }
        if (!missing.isEmpty()) {
            throw new SpreadsheetRejected("Missing required column" + (missing.size() > 1 ? "s" : "") + ": "
                    + String.join(", ", missing) + ". Download the size chart template and keep its header row.");
        }
        Set<Integer> fixedIndexes = new HashSet<>(fixed.values());
        for (int i = 0; i < header.size(); i++) {
            String raw = header.get(i);
            if (fixedIndexes.contains(i) || raw == null || raw.isBlank()) {
                continue;
            }
            String label = measurementLabel(raw);
            String key = SizeChartRules.keyFrom(label);
            if (key.isEmpty()) {
                warnings.add("Ignored column \"" + raw + "\": it has no usable name");
            } else if (measurementColumns.containsKey(key)) {
                warnings.add("Column \"" + raw + "\" repeats the measurement " + measurementLabels.get(key)
                        + "; the first one is used");
            } else {
                measurementColumns.put(key, i);
                measurementLabels.put(key, label);
            }
        }
        if (measurementColumns.isEmpty()) {
            throw new SpreadsheetRejected("Add at least one measurement column (for example Bust or Foot length) "
                    + "after the Size column");
        }
        if (measurementColumns.size() > SizeChartRules.MAX_COLUMNS) {
            throw new SpreadsheetRejected("A size chart has at most " + SizeChartRules.MAX_COLUMNS
                    + " measurement columns; the file has " + measurementColumns.size());
        }
    }

    /** "Bust (cm) *" is the measurement "Bust". */
    private static String measurementLabel(String header) {
        String h = ImportColumns.normalise(header);
        String original = CustomValues.clean(header.replace(' ', ' '));
        while (original.endsWith("*")) {
            original = original.substring(0, original.length() - 1).strip();
        }
        String withoutUnit = TRAILING_PARENTHESES.matcher(original).replaceAll("").strip();
        return withoutUnit.isEmpty() ? h : withoutUnit;
    }

    // ------------------------------------------------------------------ charts

    private ParsedChart chart(String name, List<SheetRow> rows) {
        SheetRow first = rows.getFirst();
        if (name.length() < SizeChartRules.MIN_NAME || name.length() > SizeChartRules.MAX_NAME) {
            rows.forEach(r -> add(r, error(ChartImportColumns.CHART_NAME, SizeChartRules.MIN_NAME + " to "
                    + SizeChartRules.MAX_NAME + " characters")));
            return null;
        }
        MeasurementUnit unit = unit(rows);
        FitNote fit = fit(rows);
        String howTo = productText(rows, ChartImportColumns.HOW_TO_MEASURE);
        List<ChartColumn> columns = new ArrayList<>();
        measurementLabels.forEach((key, label) -> columns.add(new ChartColumn(key, label, unit)));
        List<ChartRow> chartRows = new ArrayList<>();
        Map<String, Integer> firstRowOfSize = new HashMap<>();
        for (SheetRow r : rows) {
            chartRows.add(row(r, firstRowOfSize));
        }
        boolean clean = rows.stream().noneMatch(this::hasError);
        if (clean) {
            ChartContent content = new ChartContent(name, unit, ctx.productTypeCode(), null, columns, chartRows, fit,
                    howTo.isEmpty() ? null : howTo);
            SizeChartRules.Result checked = SizeChartRules.check(content);
            checked.errors().forEach((field, message) -> place(rows, field, message));
            if (checked.errors().isEmpty()) {
                return new ParsedChart(name, checked.content(), rows.stream().map(SheetRow::rowNumber).toList());
            }
        }
        List<SheetRow> bad = rows.stream().filter(this::hasError).toList();
        if (rows.size() > 1) {
            for (SheetRow r : rows) {
                if (!hasError(r)) {
                    add(r, error(ChartImportColumns.CHART_NAME, "Another row of this chart has errors (row "
                            + bad.getFirst().rowNumber() + "); a chart is saved whole"));
                }
            }
        }
        if (bad.isEmpty()) {
            add(first, error(ChartImportColumns.CHART_NAME, "The chart cannot be saved"));
        }
        return null;
    }

    private MeasurementUnit unit(List<SheetRow> rows) {
        MeasurementUnit unit = null;
        int unitRow = 0;
        for (SheetRow r : rows) {
            String raw = text(r, ChartImportColumns.UNIT);
            if (raw.isEmpty()) {
                continue;
            }
            MeasurementUnit u = ChartImportColumns.unitOf(raw);
            if (u == null) {
                add(r, error(ChartImportColumns.UNIT, "Write cm or in"));
            } else if (unit == null) {
                unit = u;
                unitRow = r.rowNumber();
            } else if (u != unit) {
                add(r, RowMessage.warning(ChartImportColumns.UNIT, idx(ChartImportColumns.UNIT),
                        "Ignored: the chart's unit is taken from row " + unitRow));
            }
        }
        return unit != null ? unit : ctx.defaultUnit();
    }

    private FitNote fit(List<SheetRow> rows) {
        FitNote fit = null;
        for (SheetRow r : rows) {
            String raw = text(r, ChartImportColumns.FIT_NOTE);
            if (raw.isEmpty()) {
                continue;
            }
            FitNote f = ChartImportColumns.fitOf(raw);
            if (f == null) {
                add(r, error(ChartImportColumns.FIT_NOTE, "Choose one of Runs small, True to size, Runs large"));
            } else if (fit == null) {
                fit = f;
            }
        }
        return fit;
    }

    private ChartRow row(SheetRow r, Map<String, Integer> firstRowOfSize) {
        String size = CustomValues.clean(text(r, ChartImportColumns.SIZE));
        String problem = size.isEmpty() ? "Required: the size label, for example M or UK 12"
                : CustomValues.problem("Size", size);
        if (problem != null) {
            add(r, error(ChartImportColumns.SIZE, problem));
        } else {
            Integer earlier = firstRowOfSize.putIfAbsent(CustomValues.key(size), r.rowNumber());
            if (earlier != null) {
                add(r, error(ChartImportColumns.SIZE, "Duplicate size " + size + ": also on row " + earlier
                        + " (sizes are compared ignoring case)"));
            }
        }
        Map<String, MeasurementRange> values = new LinkedHashMap<>();
        measurementColumns.forEach((key, index) -> {
            String column = ChartImportColumns.MEASUREMENT_PREFIX + key;
            String raw = r.cell(index).strip();
            if (raw.isEmpty()) {
                add(r, RowMessage.error(column, index, "Enter the " + measurementLabels.get(key).toLowerCase(
                        Locale.ROOT) + (size.isEmpty() ? "" : " of size " + size)));
                return;
            }
            MeasurementRange range = parse(raw);
            if (range == null) {
                add(r, RowMessage.error(column, index, "\"" + abbreviate(raw) + "\" is not a number or a range like "
                        + "86-90"));
            } else if (range.min().compareTo(range.max()) > 0) {
                add(r, RowMessage.error(column, index, "The smallest value (" + range.min().toPlainString()
                        + ") is above the largest (" + range.max().toPlainString() + ")"));
            } else if (range.min().signum() <= 0) {
                add(r, RowMessage.error(column, index, "A measurement above zero"));
            } else {
                values.put(key, range);
            }
        });
        return new ChartRow(size, values);
    }

    /** "88", "86-90", "86 to 90", "88 cm", "87,5"; null when it is none of those. */
    static MeasurementRange parse(String raw) {
        String s = UNIT_SUFFIX.matcher(raw.strip()).replaceAll("").strip();
        Matcher single = SINGLE.matcher(s);
        if (single.matches()) {
            return new MeasurementRange(decimal(single.group(1)), null);
        }
        Matcher range = RANGE.matcher(s);
        if (range.matches()) {
            return new MeasurementRange(decimal(range.group(1)), decimal(range.group(2)));
        }
        return null;
    }

    private static BigDecimal decimal(String s) {
        return new BigDecimal(s.replace(',', '.'));
    }

    /** Puts a rule violation of the assembled chart on the row it concerns. */
    private void place(List<SheetRow> rows, String field, String message) {
        Matcher m = ROW_FIELD.matcher(field);
        if (m.matches()) {
            SheetRow r = rows.get(Integer.parseInt(m.group(1)));
            String column = m.group(3) == null ? ChartImportColumns.SIZE
                    : ChartImportColumns.MEASUREMENT_PREFIX + m.group(3);
            add(r, RowMessage.error(column, idx(column), message));
        } else if (field.equals("name")) {
            rows.forEach(r -> add(r, error(ChartImportColumns.CHART_NAME, message)));
        } else {
            add(rows.getFirst(), error(ChartImportColumns.CHART_NAME, message));
        }
    }

    // ------------------------------------------------------------------ helpers

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
        Integer i = fixed.get(column);
        return i == null ? "" : r.cell(i).strip();
    }

    private int idx(String column) {
        if (column.startsWith(ChartImportColumns.MEASUREMENT_PREFIX)) {
            return measurementColumns.getOrDefault(column.substring(ChartImportColumns.MEASUREMENT_PREFIX.length()), -1);
        }
        return fixed.getOrDefault(column, -1);
    }

    private RowMessage error(String column, String message) {
        return RowMessage.error(column, idx(column), message);
    }

    private void add(SheetRow r, RowMessage m) {
        messages.get(r.rowNumber()).add(m);
    }

    private boolean hasError(SheetRow r) {
        return messages.get(r.rowNumber()).stream().anyMatch(m -> m.severity() == Severity.ERROR);
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 37) + "…";
    }
}
