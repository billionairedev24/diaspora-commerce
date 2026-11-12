package com.afrotransact.inventory.domain;

/** Lifecycle of a stock file import: validated (preview), then queued, running and completed. */
public enum StockImportStatus {
    VALIDATED, QUEUED, RUNNING, COMPLETED, FAILED;

    public boolean started() {
        return this != VALIDATED;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
