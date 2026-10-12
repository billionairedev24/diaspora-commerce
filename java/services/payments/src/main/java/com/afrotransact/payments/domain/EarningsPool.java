package com.afrotransact.payments.domain;

import java.util.Map;
import java.util.Set;

/**
 * What a plan fee may be withheld from (S298): the organisation's delivered but not yet transferred nets (settlement
 * hold running or matured), never orders with an open dispute, less what earlier withholdings and outstanding label
 * charges already claim on them.
 */
public final class EarningsPool {

    private EarningsPool() {
    }

    /**
     * @param payableByOrder seller payable (2000:org) per order, credit positive
     * @param delivered      orders whose settlement hold started (order or shipment delivered)
     * @param disputed       orders with an OPEN dispute: excluded entirely, whatever their payable
     * @param labelsOwed     label charges not yet recovered (positive) — recovered from the same transfers
     * @param planFeesOwed   plan fees withheld and not yet recovered (positive)
     * @return the withholdable amount, never negative
     */
    public static Money withholdable(Map<String, Long> payableByOrder, Set<String> delivered, Set<String> disputed,
                                     long labelsOwed, long planFeesOwed, String currency) {
        long eligible = 0;
        for (Map.Entry<String, Long> e : payableByOrder.entrySet()) {
            if (e.getValue() > 0 && delivered.contains(e.getKey()) && !disputed.contains(e.getKey())) {
                eligible = Math.addExact(eligible, e.getValue());
            }
        }
        long left = eligible - Math.max(0, labelsOwed) - Math.max(0, planFeesOwed);
        return Money.of(Math.max(0, left), currency);
    }
}
