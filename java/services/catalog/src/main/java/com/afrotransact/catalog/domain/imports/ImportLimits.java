package com.afrotransact.catalog.domain.imports;

/** Limits of a bulk upload, enforced while reading the file (documented in the upload page and the catalog README). */
public final class ImportLimits {

    /** Largest file accepted. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** Data rows per file (one row per variant). */
    public static final int MAX_ROWS = 5_000;
    /** Columns per row. */
    public static final int MAX_COLUMNS = 200;
    /** Characters per cell (longer cells are errors, never truncated silently). */
    public static final int MAX_CELL_CHARS = 5_000;
    /** Image URLs per product. */
    public static final int MAX_IMAGES = 10;
    /** Variants per product (rows sharing a parent SKU). */
    public static final int MAX_VARIANTS = 200;
    /** Uncompressed size of the parts of an .xlsx file (zip bomb guard). */
    public static final long MAX_UNZIPPED_BYTES = 200L * 1024 * 1024;
    /** Smallest compression ratio accepted for an .xlsx part (zip bomb guard). */
    public static final double MIN_INFLATE_RATIO = 0.01;

    private ImportLimits() {
    }
}
