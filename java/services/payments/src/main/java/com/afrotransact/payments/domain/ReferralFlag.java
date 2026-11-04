package com.afrotransact.payments.domain;

/** Why an abuse check holds a referral for staff. Each says the referrer and the friend share something. */
public enum ReferralFlag {
    SAME_DEVICE,
    SAME_NETWORK,
    SAME_ADDRESS,
    SAME_PAYMENT_METHOD
}
