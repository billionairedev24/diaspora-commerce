package com.afrotransact.taxreturns.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;

/** Parses staff CSV uploads into state rules and local rates (USA pilot; country column reserved). */
public final class TaxRatesCsvImport {

    private TaxRatesCsvImport() {
    }

    public record Parsed(List<StateTaxRule> states, List<LocalTaxRate> locals, List<String> warnings) {
    }

    public static Parsed parse(String csv) {
        List<String[]> rows = readRows(csv);
        if (rows.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.import-empty", "The CSV is empty", Map.of("csv", "Add a header row and at least one data row"));
        }
        Map<String, Integer> header = headerIndex(rows.getFirst());
        requireColumns(header, "kind", "country", "state", "name");
        List<StateTaxRule> states = new ArrayList<>();
        List<LocalTaxRate> locals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            String kind = cell(row, header, "kind").toUpperCase(Locale.ROOT);
            String country = firstNonBlank(cell(row, header, "country"), "US").toUpperCase(Locale.ROOT);
            String state = cell(row, header, "state").toUpperCase(Locale.ROOT);
            String name = cell(row, header, "name");
            if (!"US".equals(country)) {
                warnings.add("Line " + (i + 1) + ": country " + country + " is stored but only US rules are quoted today");
            }
            switch (kind) {
                case "STATE" -> states.add(parseState(country, state, name, row, header, i + 1));
                case "LOCAL" -> locals.add(parseLocal(state, name, row, header, i + 1));
                default -> throw rowError(i + 1, "kind", "STATE or LOCAL");
            }
        }
        if (states.isEmpty() && locals.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.import-empty", "No data rows", Map.of("csv", "Add STATE or LOCAL rows"));
        }
        return new Parsed(states, locals, warnings);
    }

    private static StateTaxRule parseState(String country, String state, String name, String[] row, Map<String, Integer> header,
                                           int line) {
        if (state.length() != 2) {
            throw rowError(line, "state", "two-letter code");
        }
        return new StateTaxRule(country, state, name, rate(row, header, "generalRate", line), rate(row, header, "groceryRate", line),
                rate(row, header, "preparedFoodRate", line), bool(row, header, "shippingTaxable"), bool(row, header, "facilitator"), -1L);
    }

    private static LocalTaxRate parseLocal(String state, String name, String[] row, Map<String, Integer> header, int line) {
        if (state.length() != 2) {
            throw rowError(line, "state", "two-letter code");
        }
        String zip = cell(row, header, "zipPrefix");
        if (!zip.matches("\\d{3}|\\d{5}")) {
            throw rowError(line, "zipPrefix", "3- or 5-digit ZIP prefix");
        }
        return new LocalTaxRate(state, zip, name, rate(row, header, "localRate", line), bool(row, header, "appliesToGrocery"));
    }

    private static BigDecimal rate(String[] row, Map<String, Integer> header, String col, int line) {
        String v = cell(row, header, col);
        if (v.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal r = new BigDecimal(v.trim());
            if (r.signum() < 0 || r.compareTo(new BigDecimal("0.5")) >= 0) {
                throw rowError(line, col, "between 0 and 0.5");
            }
            return r;
        } catch (NumberFormatException ex) {
            throw rowError(line, col, "a decimal fraction (0.0625 = 6.25 %)");
        }
    }

    private static boolean bool(String[] row, Map<String, Integer> header, String col) {
        String v = cell(row, header, col).trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? false : v.equals("true") || v.equals("1") || v.equals("yes");
    }

    private static List<String[]> readRows(String csv) {
        List<String[]> out = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            out.add(splitCsvLine(line));
        }
        return out;
    }

    private static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells.toArray(String[]::new);
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            idx.put(header[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return idx;
    }

    private static void requireColumns(Map<String, Integer> header, String... cols) {
        Map<String, String> missing = new LinkedHashMap<>();
        for (String c : cols) {
            if (!header.containsKey(c.toLowerCase(Locale.ROOT))) {
                missing.put(c, "Column required");
            }
        }
        if (!missing.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.import-invalid-header", "Unrecognised CSV header", missing);
        }
    }

    private static String cell(String[] row, Map<String, Integer> header, String col) {
        Integer i = header.get(col.toLowerCase(Locale.ROOT));
        if (i == null || i >= row.length) {
            return "";
        }
        return row[i].trim();
    }

    private static boolean isBlankRow(String[] row) {
        for (String c : row) {
            if (!c.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String firstNonBlank(String a, String fallback) {
        return a == null || a.isBlank() ? fallback : a;
    }

    private static ReturnRuleViolation rowError(int line, String field, String expect) {
        return new ReturnRuleViolation("tax-returns.import-row-invalid", "Invalid CSV row " + line,
                Map.of("line", String.valueOf(line), field, "Expected " + expect));
    }
}
