package com.afrotransact.catalog.domain.imports;

/**
 * Formula (CSV) injection guard for files we generate from seller input: a cell that starts with = + - @, a tab or a
 * carriage return is written with a leading apostrophe so spreadsheet apps show it as text instead of running it.
 */
public final class CellSafety {

    private CellSafety() {
    }

    public static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        char c = value.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' ? "'" + value : value;
    }
}
