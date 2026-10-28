package com.afrotransact.catalog.domain.imports;

import java.util.Locale;

/** File formats of templates, uploads and error reports. */
public enum SpreadsheetFormat {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv; charset=utf-8");

    private final String extension;
    private final String contentType;

    SpreadsheetFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** The format of a file name or format parameter ("xlsx", "products.CSV"); null when unsupported. */
    public static SpreadsheetFormat of(String nameOrFormat) {
        if (nameOrFormat == null) {
            return null;
        }
        String s = nameOrFormat.strip().toLowerCase(Locale.ROOT);
        String ext = s.contains(".") ? s.substring(s.lastIndexOf('.') + 1) : s;
        for (SpreadsheetFormat f : values()) {
            if (f.extension.equals(ext)) {
                return f;
            }
        }
        return null;
    }
}
