package com.afrotransact.catalog.domain.imports;

/** Validation result of one row. */
public enum RowStatus {
    VALID,
    /** Importable, with something the seller should know (an ignored value, a missing recommended field). */
    WARNING,
    /** Not importable until fixed. */
    ERROR
}
