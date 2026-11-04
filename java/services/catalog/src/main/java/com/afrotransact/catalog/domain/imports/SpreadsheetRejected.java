package com.afrotransact.catalog.domain.imports;

/** The uploaded file cannot be read as a product sheet at all (format, size, limits, missing columns). */
public class SpreadsheetRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SpreadsheetRejected(String message) {
        super(message);
    }

    public SpreadsheetRejected(String message, Throwable cause) {
        super(message, cause);
    }
}
