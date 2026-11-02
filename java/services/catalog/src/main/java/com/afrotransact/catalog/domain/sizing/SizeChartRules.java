package com.afrotransact.catalog.domain.sizing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CustomValues;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyRules;

/**
 * What a size chart must satisfy: a name, one to twelve measurement columns, one to sixty size rows whose labels are
 * different (ignoring case) and a value or range for every column. Pure functions; every message names the field
 * ({@code rows[2].values.bust}) so the chart editor and the bulk upload can show it where it belongs.
 */
public final class SizeChartRules {

    public static final int MIN_NAME = 2;
    public static final int MAX_NAME = 80;
    public static final int MAX_COLUMNS = 12;
    public static final int MAX_ROWS = 60;
    public static final int MAX_LABEL = 40;
    public static final int MAX_NOTE = 1000;
    /** Largest measurement, in the chart's unit. */
    public static final BigDecimal MAX_MEASUREMENT = new BigDecimal("1000");

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private SizeChartRules() {
    }

    /** The key a column gets from its label ("Foot length" becomes "foot_length"). */
    public static String keyFrom(String label) {
        String key = TaxonomyRules.slug(label).replace('-', '_');
        if (key.isEmpty()) {
            return "";
        }
        if (!Character.isLetter(key.charAt(0))) {
            key = "m_" + key;
        }
        return key.length() > 40 ? key.substring(0, 40).replaceAll("_+$", "") : key;
    }

    /** The normalised content, or a {@link CatalogRuleViolation} listing every problem by field. */
    public static ChartContent validate(ChartContent content) {
        Result result = check(content);
        if (!result.errors().isEmpty()) {
            throw new CatalogRuleViolation("The size chart has " + result.errors().size() + " problem(s)",
                    result.errors());
        }
        return result.content();
    }

    /** The content in its stored form (trimmed text, keys derived, numbers rounded) with the problems found. */
    public static Result check(ChartContent c) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = CustomValues.clean(c.name());
        if (name.length() < MIN_NAME || name.length() > MAX_NAME) {
            errors.put("name", MIN_NAME + " to " + MAX_NAME + " characters");
        } else if (name.chars().anyMatch(Character::isISOControl)) {
            errors.put("name", "Remove control characters");
        }
        if (c.unitSystem() == null) {
            errors.put("unitSystem", "Choose centimetres (CM) or inches (IN)");
        }
        MeasurementUnit unit = c.unitSystem() == null ? MeasurementUnit.CM : c.unitSystem();
        List<ChartColumn> columns = columns(c.columns(), unit, errors);
        List<ChartRow> rows = rows(c.rows(), columns, unit, errors);
        String note = c.howToMeasure() == null || c.howToMeasure().isBlank() ? null : c.howToMeasure().strip();
        if (note != null && note.length() > MAX_NOTE) {
            errors.put("howToMeasure", "At most " + MAX_NOTE + " characters");
        }
        String type = c.productTypeCode() == null || c.productTypeCode().isBlank() ? null : c.productTypeCode().strip();
        return new Result(new ChartContent(name, c.unitSystem(), type, c.categoryId(), columns, rows, c.fitNote(), note),
                errors);
    }

    private static List<ChartColumn> columns(List<ChartColumn> in, MeasurementUnit unit, Map<String, String> errors) {
        if (in.isEmpty()) {
            errors.put("columns", "Add at least one measurement, for example Bust or Foot length");
            return List.of();
        }
        if (in.size() > MAX_COLUMNS) {
            errors.put("columns", "At most " + MAX_COLUMNS + " measurements");
        }
        List<ChartColumn> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            ChartColumn col = in.get(i);
            String at = "columns[" + i + "].";
            String label = CustomValues.clean(col.label());
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                errors.put(at + "label", "1 to " + MAX_LABEL + " characters");
            } else if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                errors.put(at + "label", "Two measurements are called " + label);
            }
            String key = col.key() == null || col.key().isBlank() ? keyFrom(label) : col.key().strip();
            if (!KEY.matcher(key).matches()) {
                errors.put(at + "key", "Lower-case letters, digits and _, starting with a letter");
            } else if (!keys.add(key)) {
                errors.put(at + "key", "Used twice");
            }
            if (col.unit() != null && col.unit() != unit) {
                errors.put(at + "unit", "Use the chart's unit (" + unit.symbol() + ")");
            }
            out.add(new ChartColumn(key, label, unit));
        }
        return out;
    }

    private static List<ChartRow> rows(List<ChartRow> in, List<ChartColumn> columns, MeasurementUnit unit,
                                       Map<String, String> errors) {
        if (in.isEmpty()) {
            errors.put("rows", "Add at least one size");
            return List.of();
        }
        if (in.size() > MAX_ROWS) {
            errors.put("rows", "At most " + MAX_ROWS + " sizes");
        }
        Set<String> sizes = new HashSet<>();
        Set<String> columnKeys = new HashSet<>();
        columns.forEach(col -> columnKeys.add(col.key()));
        List<ChartRow> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            ChartRow row = in.get(i);
            String at = "rows[" + i + "].";
            String size = CustomValues.clean(row.size());
            String problem = CustomValues.problem("Size", size);
            if (problem != null) {
                errors.put(at + "size", problem);
            } else if (!sizes.add(CustomValues.key(size))) {
                errors.put(at + "size", "Duplicate size " + size + " (sizes are compared ignoring case)");
            }
            Map<String, MeasurementRange> values = new LinkedHashMap<>();
            for (ChartColumn col : columns) {
                MeasurementRange given = row.values().get(col.key());
                if (given == null) {
                    errors.put(at + "values." + col.key(), "Enter the " + col.label().toLowerCase(Locale.ROOT)
                            + " of size " + size);
                    continue;
                }
                MeasurementRange fixed = range(given, at + "values." + col.key(), errors);
                if (fixed != null) {
                    values.put(col.key(), fixed);
                }
            }
            for (String key : row.values().keySet()) {
                if (!columnKeys.contains(key)) {
                    errors.put(at + "values." + key, "Not a measurement of this chart");
                }
            }
            out.add(new ChartRow(size, values));
        }
        return out;
    }

    private static MeasurementRange range(MeasurementRange given, String field, Map<String, String> errors) {
        BigDecimal min = round(given.min());
        BigDecimal max = round(given.max());
        if (min.signum() <= 0 || max.signum() <= 0) {
            errors.put(field, "A measurement above zero");
            return null;
        }
        if (min.compareTo(MAX_MEASUREMENT) > 0 || max.compareTo(MAX_MEASUREMENT) > 0) {
            errors.put(field, "At most " + MAX_MEASUREMENT.toPlainString());
            return null;
        }
        if (min.compareTo(max) > 0) {
            errors.put(field, "The smallest value (" + min.toPlainString() + ") is above the largest ("
                    + max.toPlainString() + ")");
            return null;
        }
        return new MeasurementRange(min, max);
    }

    /** Two decimals at most, trailing zeros dropped (88.50 is 88.5). */
    public static BigDecimal round(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }

    /** The content in stored form and the problems found in it. */
    public record Result(ChartContent content, Map<String, String> errors) {

        public Result {
            errors = Map.copyOf(errors);
        }
    }
}
