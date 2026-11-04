package com.afrotransact.catalog.port;

import java.util.List;

import com.afrotransact.catalog.domain.imports.ReportRow;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.TemplateSpec;

/** Writes upload templates and error reports. */
public interface SpreadsheetWriter {

    byte[] template(TemplateSpec spec, SpreadsheetFormat format);

    /** The uploaded rows with "Row status", "Result" and "Errors" columns; every cell is formula-injection safe. */
    byte[] report(List<String> header, List<ReportRow> rows, SpreadsheetFormat format);
}
