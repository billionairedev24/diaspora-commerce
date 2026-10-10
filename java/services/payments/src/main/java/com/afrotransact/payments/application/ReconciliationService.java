package com.afrotransact.payments.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.payments.domain.ReconciliationReport;

/** Daily comparison of ledger 1000 Stripe clearing with Stripe balance transactions. */
public interface ReconciliationService {

    /** Manual run for one day (payments:configure). */
    ReconciliationReport reconcile(LocalDate day);

    /** Scheduled run (system). */
    ReconciliationReport runDaily(LocalDate day);

    List<ReconciliationReport> recent(int limit);
}
