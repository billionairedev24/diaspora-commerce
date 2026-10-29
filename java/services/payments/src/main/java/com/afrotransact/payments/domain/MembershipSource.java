package com.afrotransact.payments.domain;

/** How a membership is paid for. */
public enum MembershipSource {
    /** Billed on the member's saved card. */
    PAID,
    /** Free days a program (referral) granted: no card, ends on its date. */
    COMPLIMENTARY
}
