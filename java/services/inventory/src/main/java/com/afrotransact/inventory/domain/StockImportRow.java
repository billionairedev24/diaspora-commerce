package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * One row of a stock file: what it asks for (validated) and what the job did with it.
 *
 * @param quantity the counted quantity (ABSOLUTE) or the change (DELTA); null when the row has errors
 * @param lotNo    the lot the row names; null when the SKU has one lot at the location
 * @param expiry   narrows the lot by its expiry date (perishables); null when not given
 * @param cells    the cells as uploaded, for the preview and the report
 * @param before   units on hand before the row was applied; null until applied
 */
public record StockImportRow(int rowNumber, String sku, String nodeId, String lotNo, Integer quantity,
                             LocalDate expiry, String status, List<StockRowMessage> messages, List<String> cells,
                             StockRowOutcome outcome, String outcomeNote, Integer before, Integer after) {

    public static final String VALID = "VALID";
    public static final String WARNING = "WARNING";
    public static final String ERROR = "ERROR";

    public StockImportRow {
        messages = List.copyOf(messages);
        cells = List.copyOf(cells);
    }

    /** A validated row that nothing has happened to yet. */
    public static StockImportRow validated(int rowNumber, String sku, String nodeId, String lotNo, Integer quantity,
                                           LocalDate expiry, List<StockRowMessage> messages, List<String> cells) {
        String status = messages.stream().anyMatch(StockRowMessage::isError) ? ERROR
                : messages.isEmpty() ? VALID : WARNING;
        return new StockImportRow(rowNumber, sku, nodeId, lotNo, quantity, expiry, status, messages, cells,
                StockRowOutcome.PENDING, null, null, null);
    }

    public boolean importable() {
        return !ERROR.equals(status);
    }

    /** The messages joined for the report. */
    public String summary() {
        return String.join("; ", messages.stream().map(m -> (m.isError() ? "" : "Warning: ") + m.message()).toList());
    }
}
