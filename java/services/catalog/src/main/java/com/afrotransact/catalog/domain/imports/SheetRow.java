package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * One data row as read from the file.
 *
 * @param rowNumber the row number the seller sees in the spreadsheet (header = 1)
 * @param cells     cell text in header order (formulas as their cached value); shorter than the header when the
 *                  trailing cells are empty
 */
public record SheetRow(int rowNumber, List<String> cells) {

    public SheetRow {
        cells = List.copyOf(cells);
    }

    public String cell(int index) {
        return index < 0 || index >= cells.size() ? "" : cells.get(index);
    }

    public boolean blank() {
        return cells.stream().allMatch(c -> c == null || c.isBlank());
    }
}
