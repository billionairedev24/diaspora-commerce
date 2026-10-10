package com.afrotransact.retail.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Sending POs to suppliers (S225).
 *
 * @param maxAttempts    attempts per channel before a dispatch is FAILED
 * @param linkTtl        validity requested for the emailed signed PDF link (media caps it per purpose)
 * @param emailTemplate  notifications template key of the supplier email
 * @param senderId       our ISA sender id in X12 envelopes
 */
public record DispatchSettings(int maxAttempts, Duration linkTtl, String emailTemplate, String senderId) {

    public DispatchSettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(linkTtl, "linkTtl");
        Objects.requireNonNull(emailTemplate, "emailTemplate");
    }
}
