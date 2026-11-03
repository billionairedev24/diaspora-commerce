package com.afrotransact.catalog.domain.imports;

import java.util.List;

import com.afrotransact.catalog.domain.sizing.ChartContent;

/**
 * The outcome of validating a size chart sheet.
 *
 * @param rows       every data row with its messages (group key = chart name, SKU column = size)
 * @param charts     the charts that can be saved: every row of them is valid or only warned about
 * @param chartCount the charts the file names, importable or not
 */
public record ChartImportResult(List<String> header, List<String> fileWarnings, List<ValidatedRow> rows,
                                List<ParsedChart> charts, int chartCount) {

    public ChartImportResult {
        header = List.copyOf(header);
        fileWarnings = List.copyOf(fileWarnings);
        rows = List.copyOf(rows);
        charts = List.copyOf(charts);
    }

    /** A chart read from the sheet, normalised as {@code SizeChartRules} stores it. */
    public record ParsedChart(String name, ChartContent content, List<Integer> rowNumbers) {

        public ParsedChart {
            rowNumbers = List.copyOf(rowNumbers);
        }
    }
}
