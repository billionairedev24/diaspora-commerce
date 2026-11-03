package com.afrotransact.catalog.domain.imports;

import java.util.List;

/** The header and data rows of an uploaded file (blank rows and {@code #} comment rows already skipped). */
public record ParsedSheet(List<String> header, List<SheetRow> rows) {

    public ParsedSheet {
        header = List.copyOf(header);
        rows = List.copyOf(rows);
    }
}
