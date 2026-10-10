package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.MfaFactor;

/** A second-factor code for an MFA challenge; {@code trustDevice} returns a 30-day trusted-device token. */
public record SecondFactorSubmission(UUID challengeId, MfaFactor factor, String code, boolean trustDevice,
                                     ClientContext context) {
}
