package com.afrotransact.payments.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Every reference present on either side must have the same net; anything else is drift. */
public class DefaultReconciler implements Reconciler {

    @Override
    public List<ReconciliationDrift> compare(Map<String, Long> ledgerByRef, Map<String, Long> stripeByRef) {
        TreeSet<String> refs = new TreeSet<>(ledgerByRef.keySet());
        refs.addAll(stripeByRef.keySet());
        List<ReconciliationDrift> drifts = new ArrayList<>();
        for (String ref : refs) {
            long ledger = ledgerByRef.getOrDefault(ref, 0L);
            long stripe = stripeByRef.getOrDefault(ref, 0L);
            if (ledger != stripe) {
                drifts.add(new ReconciliationDrift(ref, ledger, stripe));
            }
        }
        return drifts;
    }
}
