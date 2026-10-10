package com.afrotransact.identity.application;

import java.time.Instant;

/** The account phone and whether (and when) it was confirmed. */
public record PhoneVerificationView(String phone, boolean phoneVerified, Instant verifiedAt) {
}
