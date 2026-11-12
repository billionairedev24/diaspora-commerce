package com.afrotransact.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the CSV of stock files (RFC 4180: quoted cells, doubled quotes, commas or semicolons, any line ending,
 * an optional byte order mark). Writing neutralises cells a spreadsheet would run as a formula.
 */
public final class StockCsv {

    private StockCsv() {
    }

    /**
     * The records of the file. Throws {@link InventoryRuleViolation} when the file has more than {@code maxRecords} records
     * or an unclosed quote.
     */
    public static List<List<String>> parse(byte[] content, int maxRecords) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        char delimiter = delimiterOf(text);
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                records.add(row);
                row = new ArrayList<>();
                if (records.size() > maxRecords) {
                    throw tooMany(maxRecords);
                }
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new InventoryRuleViolation("inventory.upload-rejected", "The file has a quote that is never closed");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            records.add(row);
        }
        if (records.size() > maxRecords) {
            throw tooMany(maxRecords);
        }
        return records;
    }

    private static InventoryRuleViolation tooMany(int max) {
        return new InventoryRuleViolation("inventory.upload-rejected", "The file has more than " + max
                + " rows; split it into several files");
    }

    /** Semicolons when the header line has more of them than commas (European spreadsheets). */
    private static char delimiterOf(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        long commas = first.chars().filter(ch -> ch == ',').count();
        long semicolons = first.chars().filter(ch -> ch == ';').count();
        return semicolons > commas ? ';' : ',';
    }

    /** The file for these records, one per line, every cell safe to open in a spreadsheet. */
    public static byte[] write(List<List<String>> records) {
        StringBuilder out = new StringBuilder();
        for (List<String> record : records) {
            for (int i = 0; i < record.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(safe(record.get(i))));
            }
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A cell starting with = + - or @ is prefixed with an apostrophe so no spreadsheet runs it as a formula. */
    public static String safe(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        char first = cell.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + cell : cell;
    }

    private static String quote(String cell) {
        boolean needs = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0
                || cell.indexOf('\r') >= 0;
        return needs ? '"' + cell.replace("\"", "\"\"") + '"' : cell;
    }
}
