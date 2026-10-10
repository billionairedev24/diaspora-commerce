package com.afrotransact.offers.application;

import java.util.Collection;
import java.util.List;

import com.afrotransact.offers.port.BuyBoxRepository.AuditEntry;

/** Buy box recomputation (on offer, price, stock, product and seller changes), explanation and audit trail. */
public interface BuyBoxService {

    /**
     * Recomputes the buy box of each ATIN in every currency it has offers in, stores the result and, when the
     * outcome changed, audits it and publishes {@code offers.buybox.changed}. Runs in the caller's transaction.
     */
    void recompute(Collection<String> atins, String trigger);

    /** Scores of every offer (staff) or of the winner and the caller's own offers (sellers). */
    BuyBoxExplanation explain(String atin, String currency);

    /** Winner changes of an ATIN, newest first (staff). */
    List<AuditEntry> history(String atin, int limit);
}
