package com.afrotransact.trustops.application;

import java.time.Instant;

import com.afrotransact.trustops.domain.Claim;

/** A claim with its live SLA state (seller window while AWAITING_SELLER, decision timer afterwards). */
public record ClaimView(Claim claim, SlaView sla) {

    static ClaimView of(Claim claim, Instant now) {
        return new ClaimView(claim, claim.open() ? SlaView.of(claim.sla(), now) : null);
    }
}
