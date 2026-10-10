package com.afrotransact.content.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.newsletter.TokenPurpose;

/** Signs and verifies the links in newsletter emails (HMAC; no state is stored). */
public interface NewsletterTokens {

    String issue(UUID subscriptionId, TokenPurpose purpose, Instant expiresAt);

    /** The claims when the signature is valid and the token has not expired at {@code now}. */
    Optional<Claims> verify(String token, Instant now);

    /**
     * Verified token contents.
     *
     * @param subscriptionId subscription
     * @param purpose        what the link may do
     * @param expiresAt      expiry
     */
    record Claims(UUID subscriptionId, TokenPurpose purpose, Instant expiresAt) {
    }
}
