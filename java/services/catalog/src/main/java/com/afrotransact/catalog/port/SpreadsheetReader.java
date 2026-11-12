package com.afrotransact.catalog.port;

import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;

/** Reads an uploaded .xlsx or .csv file into header and rows within the import limits. */
public interface SpreadsheetReader {

    /** Throws SpreadsheetRejected for unreadable or oversized files (wrong format, zip bomb, too many rows). */
    default ParsedSheet read(byte[] content, SpreadsheetFormat format) {
        return read(content, format, ImportLimits.MAX_ROWS, ImportLimits.MAX_FILE_BYTES);
    }

    /** As {@link #read(byte[], SpreadsheetFormat)} with the limits of the upload's mode (feeds allow more rows and bytes). */
    ParsedSheet read(byte[] content, SpreadsheetFormat format, int maxRows, long maxBytes);
}
