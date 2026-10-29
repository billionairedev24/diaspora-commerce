package com.afrotransact.catalog.domain.imports;

/** Lifecycle of a bulk upload. */
public enum ImportStatus {
    /** Parsed and validated; the seller reviews the preview and starts the import (or fixes and re-uploads). */
    VALIDATED,
    /** Started; waiting for the job to pick it up. */
    QUEUED,
    /** The job is creating or updating products; resumable after a crash. */
    RUNNING,
    /** Every importable row was processed (some may have failed). */
    COMPLETED,
    /** The job stopped for good (e.g. the product type was removed). */
    FAILED;

    public boolean started() {
        return this != VALIDATED;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
