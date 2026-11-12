package com.afrotransact.offers.application;

import java.util.Map;

/**
 * The tax question of an offer (S384): does the offering seller have a stored, RESOLVED tax decision on this ATIN in
 * tax-returns? It asks first; when there is none it asks tax-returns to decide from the page's facts (never a default
 * rate). Anything but RESOLVED, and tax-returns being unreachable, means the offer is held.
 */
public interface OfferTaxGate {

    /** No permission check: callers (offer create, the release job) have done theirs. */
    Verdict check(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour);

    /**
     * @param resolved   the seller has a RESOLVED decision: the offer may be live
     * @param holdReason why it must wait: TAX_DECISION_MISSING (none yet), TAX_&lt;STATUS&gt; (tax-returns says why) or
     *                   TAX_UNAVAILABLE (tax-returns could not be asked); null when resolved
     */
    record Verdict(boolean resolved, String holdReason) {

        public static Verdict ok() {
            return new Verdict(true, null);
        }

        public static Verdict held(String reason) {
            return new Verdict(false, reason);
        }
    }
}
