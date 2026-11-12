package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * One row of the report: the cells as uploaded plus the row's status, import result and messages.
 *
 * @param errors   the row's messages in plain words (the report's Message column)
 * @param atin     the page or variant the row ended on (created, matched or held); blank when none
 * @param severity Error or Warning (the worst message of the row); blank when the row has no message
 * @param column   the column the first message is about, as the seller's header names it; blank for the row as a whole
 */
public record ReportRow(int rowNumber, List<String> cells, RowStatus status, String result, String errors, String atin,
                        String severity, String column) {

    public ReportRow {
        cells = List.copyOf(cells);
    }

    /** A row without the ATIN, severity and column columns. */
    public ReportRow(int rowNumber, List<String> cells, RowStatus status, String result, String errors) {
        this(rowNumber, cells, status, result, errors, "", "", "");
    }
}
