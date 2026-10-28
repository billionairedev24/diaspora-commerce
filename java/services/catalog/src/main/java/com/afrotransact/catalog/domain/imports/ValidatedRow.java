package com.afrotransact.catalog.domain.imports;

import java.util.List;

/** A data row with its validation result; {@code cells} are kept as uploaded for the preview and error report. */
public record ValidatedRow(int rowNumber, String groupKey, String sku, RowStatus status, List<RowMessage> messages,
                           List<String> cells) {

    public ValidatedRow {
        messages = List.copyOf(messages);
        cells = List.copyOf(cells);
    }

    /** The messages joined for the report's Errors column. */
    public String summary() {
        return String.join("; ", messages.stream()
                .map(m -> (m.severity() == Severity.WARNING ? "Warning: " : "") + m.message())
                .toList());
    }
}
