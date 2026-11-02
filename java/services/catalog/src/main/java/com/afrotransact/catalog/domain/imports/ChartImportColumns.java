package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SuggestedMeasurement;

/**
 * The size chart upload: one row per size, rows with the same chart name form one chart. Fixed columns are the chart
 * name, the unit, the size, the fit note and how to measure; every other column is a measurement (the category's
 * suggested ones are in the template, sellers add or rename their own).
 */
public final class ChartImportColumns {

    public static final String CHART_NAME = "chart_name";
    public static final String UNIT = "unit";
    public static final String SIZE = "size";
    public static final String FIT_NOTE = "fit_note";
    public static final String HOW_TO_MEASURE = "how_to_measure";
    public static final String MEASUREMENT_PREFIX = "m:";
    public static final String SHEET = "Size charts";

    /** Unit labels in the template; codes (CM, IN) and words (cm, inch, inches) are accepted too. */
    public static final List<String> UNIT_LABELS = List.of("cm", "in");

    private static final Map<String, MeasurementUnit> UNIT_WORDS = Map.of("cm", MeasurementUnit.CM,
            "centimetre", MeasurementUnit.CM, "centimetres", MeasurementUnit.CM, "centimeter", MeasurementUnit.CM,
            "centimeters", MeasurementUnit.CM, "in", MeasurementUnit.IN, "inch", MeasurementUnit.IN,
            "inches", MeasurementUnit.IN, "\"", MeasurementUnit.IN);

    private static final List<String> INSTRUCTIONS = List.of(
            "One row per size. Rows with the same Size chart name make one chart; write the name, unit, fit and how to "
                    + "measure on the chart's first row.",
            "One column per measurement. The columns after Size are the suggestions of this category: keep, rename or "
                    + "delete them, and add your own (any new column header is a measurement, e.g. \"Shoulder\").",
            "Enter a number (88) or a range (86-90) in the unit of the chart. Every size needs a value for every "
                    + "measurement column.",
            "Size is the label you use on your product variants, for example S, M, UK 12 or Free size. Sizes of one chart "
                    + "must differ (XL and xl are the same size).",
            "Re-uploading a chart with the same name updates it instead of creating a second one. A size that your "
                    + "products still sell cannot be removed from a chart.",
            "Upload as .xlsx or .csv: at most 10 MB and 5,000 rows per file. Use Preview first to check the rows without "
                    + "saving anything.");

    private ChartImportColumns() {
    }

    /** The fixed columns, then the product type's suggested measurements (none for the generic template). */
    public static List<ImportColumn> columns(ProductType type) {
        List<ImportColumn> cols = new ArrayList<>();
        cols.add(new ImportColumn(CHART_NAME, "Size chart name", "The chart's name; your products refer to it by this "
                + "name. Same name on a re-upload updates the chart.", true, ColumnKind.TEXT, List.of(),
                type == null ? "Women's dresses" : type.label() + " chart", true));
        cols.add(new ImportColumn(UNIT, "Unit", "The unit of every measurement of this chart: cm or in. The other unit "
                + "is shown to shoppers by conversion. Default cm.", false, ColumnKind.ENUM, UNIT_LABELS, "cm", true));
        cols.add(new ImportColumn(SIZE, "Size", "The size label, as on your product variants.", true, ColumnKind.TEXT,
                List.of(), "M", false));
        List<SuggestedMeasurement> suggested = type == null ? List.of()
                : type.sizing().map(s -> s.suggestedMeasurements()).orElse(List.of());
        for (SuggestedMeasurement m : suggested) {
            cols.add(measurement(m.key(), m.label(), m.help()));
        }
        cols.add(new ImportColumn(FIT_NOTE, "Fit", "How the sizes fit: Runs small, True to size or Runs large.", false,
                ColumnKind.ENUM, ImportColumns.FIT_LABELS, "True to size", true));
        cols.add(new ImportColumn(HOW_TO_MEASURE, "How to measure", "Tell shoppers how to take their measurements "
                + "(up to 1,000 characters).", false, ColumnKind.LONG_TEXT, List.of(),
                "Measure over underwear, keeping the tape level.", true));
        return List.copyOf(cols);
    }

    private static ImportColumn measurement(String key, String label, String help) {
        return new ImportColumn(MEASUREMENT_PREFIX + key, label, (help == null ? "" : help + ". ")
                + "A number (88) or a range (86-90), in the chart's unit.", true, ColumnKind.TEXT, List.of(),
                exampleFor(key), false);
    }

    private static String exampleFor(String key) {
        return switch (key) {
            case "bust", "chest" -> "88-92";
            case "waist" -> "70-74";
            case "hip" -> "96-100";
            default -> "24";
        };
    }

    /** The template of the size chart upload: a product type's suggested measurements, or the generic one. */
    public static TemplateSpec template(ProductType type) {
        List<ImportColumn> columns = columns(type);
        if (type == null) {
            List<ImportColumn> generic = new ArrayList<>(columns);
            generic.add(3, measurement("chest", "Chest", "Around the fullest part of the chest"));
            generic.add(4, measurement("waist", "Waist", "Around the natural waist"));
            columns = List.copyOf(generic);
        }
        List<List<String>> examples = new ArrayList<>();
        String[] sizes = type == null || suggestedSizes(type).isEmpty() ? new String[] {"S", "M", "L"}
                : suggestedSizes(type).stream().limit(3).toArray(String[]::new);
        for (int i = 0; i < sizes.length; i++) {
            List<String> row = new ArrayList<>();
            for (ImportColumn c : columns) {
                row.add(exampleCell(c, i, sizes[i]));
            }
            examples.add(row);
        }
        String title = type == null ? "Size charts" : "Size charts for " + type.label();
        String stem = type == null ? "afrotransact-size-charts" : "afrotransact-size-charts-" + type.code();
        return new TemplateSpec(title, stem, columns, INSTRUCTIONS, examples, SHEET);
    }

    private static List<String> suggestedSizes(ProductType type) {
        return type.sizing().flatMap(s -> type.themes().stream().map(t -> t.dimension(s.sizeDimension()))
                .filter(d -> d != null).findFirst()).map(d -> d.options()).orElse(List.of());
    }

    private static String exampleCell(ImportColumn c, int row, String size) {
        if (SIZE.equals(c.key())) {
            return size;
        }
        if (c.key().startsWith(MEASUREMENT_PREFIX)) {
            String[] parts = c.example().split("-");
            if (parts.length == 2) {
                int low = Integer.parseInt(parts[0].strip()) + row * 4;
                int high = Integer.parseInt(parts[1].strip()) + row * 4;
                return low + "-" + high;
            }
            return Integer.toString(Integer.parseInt(c.example().strip()) + row);
        }
        return row == 0 ? c.example() : "";
    }

    /** The unit named by a cell ("cm", "IN", "inches"), or null when it names none. */
    public static MeasurementUnit unitOf(String raw) {
        return raw == null ? null : UNIT_WORDS.get(raw.strip().toLowerCase(Locale.ROOT));
    }

    /** The fit note named by a cell (label or code), or null. */
    public static FitNote fitOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        for (Map.Entry<String, FitNote> e : ImportColumns.FIT_NOTES.entrySet()) {
            if (e.getKey().equalsIgnoreCase(s)) {
                return e.getValue();
            }
        }
        String code = s.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (FitNote f : FitNote.values()) {
            if (f.name().equals(code)) {
                return f;
            }
        }
        return null;
    }
}
