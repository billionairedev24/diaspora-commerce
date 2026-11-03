package com.afrotransact.catalog.domain.imports;

import java.util.List;

/** One row of the error report: the cells as uploaded plus the row's status, import result and messages. */
public record ReportRow(int rowNumber, List<String> cells, RowStatus status, String result, String errors) {

    public ReportRow {
        cells = List.copyOf(cells);
    }
}
