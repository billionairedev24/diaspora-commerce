package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.UUID;

/** A row of a bulk upload as stored: its validation result and what the import job did with it. */
public record StoredRow(int rowNumber, String groupKey, String sku, RowStatus status, List<RowMessage> messages,
                        List<String> cells, RowOutcome outcome, String outcomeNote, UUID productId) {

    public StoredRow {
        messages = List.copyOf(messages);
        cells = List.copyOf(cells);
    }
}
