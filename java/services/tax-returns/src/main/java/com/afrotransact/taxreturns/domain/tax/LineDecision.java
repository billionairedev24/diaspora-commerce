package com.afrotransact.taxreturns.domain.tax;

import java.util.Map;

import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;

/**
 * The stored tax decision a quote line was taxed under. Anything but RESOLVED means the line carries no tax figure and
 * checkout must block it.
 *
 * @param status        decision status
 * @param outcome       EXEMPT or TAXABLE when RESOLVED
 * @param internalTaxCode outcome-level code
 * @param providerCodes provider code per provider for this outcome (only codes usable for a quote)
 * @param ruleVersion   rule version of the decision
 * @param blockedReason plain message for the shopper when the line cannot be taxed
 * @param categoryCode  category the decision came from
 * @param detail        internal explanation (logs, reports), never shown to shoppers
 */
public record LineDecision(ResolveStatus status, Outcome outcome, String internalTaxCode,
                           Map<String, String> providerCodes, String ruleVersion, String blockedReason,
                           String categoryCode, String detail) {

    /** The message shoppers see for any line that cannot be taxed. */
    public static final String BLOCKED_MESSAGE = "This item can't be bought right now. We're checking its tax details. "
            + "Please remove it or try again later.";

    public LineDecision {
        providerCodes = providerCodes == null ? Map.of() : Map.copyOf(providerCodes);
    }

    public static LineDecision blocked(ResolveStatus status, String ruleVersion, String categoryCode, String detail) {
        return new LineDecision(status, null, null, Map.of(), ruleVersion, BLOCKED_MESSAGE, categoryCode, detail);
    }

    public boolean taxable() {
        return status == ResolveStatus.RESOLVED && outcome == Outcome.TAXABLE;
    }

    public boolean exempt() {
        return status == ResolveStatus.RESOLVED && outcome == Outcome.EXEMPT;
    }

    public boolean isBlocked() {
        return status != ResolveStatus.RESOLVED;
    }
}
