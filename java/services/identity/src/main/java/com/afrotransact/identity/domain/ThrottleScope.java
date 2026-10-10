package com.afrotransact.identity.domain;

/** What a sign-in throttle counts failures for. */
public enum ThrottleScope {
    /** Per account, keyed by the normalised email (also for emails without an account, to avoid enumeration). */
    ACCOUNT,
    /** Per client IP address (the end user's address as reported by the BFF, or the hosted page's remote address). */
    IP
}
