package com.afrotransact.inventory.domain;

/** How the quantity column of a stock file is read (S395). */
public enum StockImportMode {
    /** The column is what is on hand now (a count). The same file again changes nothing. */
    ABSOLUTE,
    /** The column is a change (+5, -3) on what is on hand now. The same file is never applied twice. */
    DELTA
}
