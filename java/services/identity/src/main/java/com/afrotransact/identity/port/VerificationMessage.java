package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.VerificationPurpose;

/**
 * A one-time code to deliver.
 *
 * @param channel     EMAIL or SMS
 * @param destination email address or E.164 phone number
 */
public record VerificationMessage(UUID userId, VerificationPurpose purpose, String channel, String destination,
                                  String firstName, String code, Instant expiresAt) {
}
