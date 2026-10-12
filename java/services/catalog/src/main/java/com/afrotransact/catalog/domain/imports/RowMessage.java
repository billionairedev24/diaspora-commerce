package com.afrotransact.catalog.domain.imports;

/**
 * A problem found in a row.
 *
 * @param column      the column key ({@code price}, {@code attr:material}); null for the row as a whole
 * @param columnIndex position of the column in the uploaded header; -1 when the column is missing or for the row
 */
public record RowMessage(String column, int columnIndex, Severity severity, String message) {

    public static RowMessage error(String column, int columnIndex, String message) {
        return new RowMessage(column, columnIndex, Severity.ERROR, message);
    }

    public static RowMessage warning(String column, int columnIndex, String message) {
        return new RowMessage(column, columnIndex, Severity.WARNING, message);
    }
}
