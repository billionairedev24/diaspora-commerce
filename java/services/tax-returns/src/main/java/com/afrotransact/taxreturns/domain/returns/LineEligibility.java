package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;
import java.util.Set;

/**
 * Whether and how a delivered line can be returned.
 *
 * @param lineId            order line id
 * @param eligible          something can be requested for the line
 * @param reportProblemOnly non-returnable (perishable): only "report a problem" with a seller-fault reason, no return
 * @param sellerFaultOnly   the seller does not accept no-fault returns
 * @param maxQuantity       units that can still be returned
 * @param windowEndsAt      end of the return window (null when not delivered)
 * @param methods           allowed methods
 * @param ineligibleCode    why not eligible (null when eligible): not-delivered, window-closed, already-returned
 */
public record LineEligibility(String lineId, boolean eligible, boolean reportProblemOnly, boolean sellerFaultOnly,
                              int maxQuantity, Instant windowEndsAt, Set<ReturnMethod> methods,
                              String ineligibleCode) {

    public LineEligibility {
        methods = Set.copyOf(methods);
    }
}
