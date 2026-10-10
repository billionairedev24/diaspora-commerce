package com.afrotransact.inventory.application;

import java.util.Optional;

import com.afrotransact.inventory.domain.ReconciliationReport;

/** Recomputes positions from the ledger and from lots and reports drift (gauge inventory_reconciliation_drift). */
public interface ReconciliationService {

    /** Scheduled nightly run (no actor). */
    ReconciliationReport reconcile();

    /** On-demand run (inventory:adjust). */
    ReconciliationReport runNow();

    /** Latest stored run (inventory:read, staff). */
    Optional<ReconciliationReport> latest();
}
