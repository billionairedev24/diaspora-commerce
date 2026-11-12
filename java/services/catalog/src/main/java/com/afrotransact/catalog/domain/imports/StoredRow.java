package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.UUID;

/**
 * A row of a bulk upload as stored: its validation result and what the import job did with it.
 *
 * @param atin    the page or variant ATIN the row ended on or matched (offer rows, S393); null when none
 * @param offerId the offer the row created or changed; null when none
 */
public record StoredRow(int rowNumber, String groupKey, String sku, RowStatus status, List<RowMessage> messages,
                        List<String> cells, RowOutcome outcome, String outcomeNote, UUID productId, String atin,
                        UUID offerId) {

    public StoredRow {
        messages = List.copyOf(messages);
        cells = List.copyOf(cells);
    }

    /** A row not tied to an ATIN or an offer. */
    public StoredRow(int rowNumber, String groupKey, String sku, RowStatus status, List<RowMessage> messages,
                     List<String> cells, RowOutcome outcome, String outcomeNote, UUID productId) {
        this(rowNumber, groupKey, sku, status, messages, cells, outcome, outcomeNote, productId, null, null);
    }
}
