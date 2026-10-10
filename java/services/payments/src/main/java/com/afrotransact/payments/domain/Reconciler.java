package com.afrotransact.payments.domain;

import java.util.List;
import java.util.Map;

/** Compares ledger movements and Stripe balance transactions per Stripe source object. */
public interface Reconciler {

    /**
     * @param ledgerByRef net 1000 Stripe clearing movement per Stripe reference (debit positive)
     * @param stripeByRef net of Stripe balance transactions per source (credit to the balance positive)
     */
    List<ReconciliationDrift> compare(Map<String, Long> ledgerByRef, Map<String, Long> stripeByRef);
}
