package com.afrotransact.inventory.port;

import java.util.Optional;

import com.afrotransact.inventory.domain.ReconciliationReport;

/** Stored reconciliation runs. */
public interface ReconciliationRepository {

    void save(ReconciliationReport report);

    Optional<ReconciliationReport> latest();
}
