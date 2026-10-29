package com.afrotransact.payments.domain;

/** What Finance proposes for an open transfer recovery. */
public enum RecoveryResolution {
    /** The seller paid the platform back outside Stripe (bank transfer): Dr 1050 / Cr 2000:org. */
    RECOVERED,
    /** The platform absorbs the rest: Dr 5300 / Cr 2000:org. */
    WRITE_OFF
}
