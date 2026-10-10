package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.LabelCharge;
import com.afrotransact.payments.domain.LabelChargeStatus;

/**
 * Shipping labels sellers buy through AfroTransact (S256). A seller-shipped label is debited from the seller's payable
 * at the carrier's cost plus handling, even when that takes the balance below zero; a void credits it back once.
 * AfroTransact Retail labels and labels of FBA / AfroTransact Delivery shipments (and returns) are never charged.
 */
public interface LabelChargeService {

    /** Charges a purchased label (once per event and per label). Returns false when nothing was charged. */
    boolean purchased(LabelChargeNotice notice);

    /** Credits a voided label back (once per label). Returns false when nothing was credited. */
    boolean voided(LabelChargeNotice notice);

    /**
     * The organisation's label charges, newest first (its members, or staff).
     *
     * @param status CHARGED or CREDITED (null: both)
     * @param from   purchased at or after (null: no lower bound)
     * @param to     purchased before (null: no upper bound)
     */
    List<LabelCharge> ofOrg(String orgId, LabelChargeStatus status, Instant from, Instant to, int limit);
}
