package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A JWKS signing key. Only the public half is part of this record; the private half stays sealed in the store and is
 * erased when the key stops being active.
 *
 * @param retireAfter when a RETIRING key leaves the JWKS (the longest access token issued under it has expired)
 */
public record SigningKey(String kid, String algorithm, SigningKeyStatus status, String publicKeyPem, Instant createdAt,
                         Instant rotatedAt, Instant retireAfter, Instant retiredAt) {

    /** Extra time beyond the longest access token for clock skew between services. */
    public static final Duration SKEW = Duration.ofMinutes(2);

    /** Binds a sealed private key to its row, like the TOTP secrets: another row's ciphertext does not open. */
    public static String sealContext(String kid) {
        return "signing_key:" + kid;
    }

    /** The status as of {@code now}: a RETIRING key past its date reads as RETIRED even before it is persisted so. */
    public SigningKeyStatus statusAt(Instant now) {
        if (status == SigningKeyStatus.RETIRING && retireAfter != null && !retireAfter.isAfter(now)) {
            return SigningKeyStatus.RETIRED;
        }
        return status;
    }

    public boolean publishedAt(Instant now) {
        return statusAt(now) != SigningKeyStatus.RETIRED;
    }

    /** When the key that is rotated out at {@code rotatedAt} may leave the JWKS. */
    public static Instant retireAfter(Instant rotatedAt, Duration longestAccessToken) {
        return rotatedAt.plus(longestAccessToken).plus(SKEW);
    }
}
