package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.OptionalLong;

/** RFC 6238 time-based one-time passwords (authenticator apps). */
public interface TotpAuthenticator {

    int DIGITS = 6;
    int PERIOD_SECONDS = 30;

    /** New random base32 secret (160 bits). */
    String newSecret();

    /** {@code otpauth://} URI rendered as a QR code by the client. */
    String otpauthUri(String issuer, String account, String secret);

    String codeAt(String secret, long timeStep);

    /**
     * Checks {@code code} against the current step ±1 (clock drift). Returns the matched time step, which must be
     * greater than {@code lastUsedStep} so a code cannot be replayed.
     */
    OptionalLong verify(String secret, String code, Instant now, long lastUsedStep);
}
