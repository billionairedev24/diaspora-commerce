package com.afrotransact.catalog.port;

import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;

/** Reads an uploaded .xlsx or .csv file into header and rows within the import limits. */
public interface SpreadsheetReader {

    /** Throws SpreadsheetRejected for unreadable or oversized files (wrong format, zip bomb, too many rows). */
    ParsedSheet read(byte[] content, SpreadsheetFormat format);
}
