package com.afrotransact.payments.port;

import java.util.List;

import com.afrotransact.payments.domain.ReconciliationReport;

/** Reconciliation runs. */
public interface ReconciliationRepository {

    void insert(ReconciliationReport report);

    List<ReconciliationReport> recent(int limit);
}
