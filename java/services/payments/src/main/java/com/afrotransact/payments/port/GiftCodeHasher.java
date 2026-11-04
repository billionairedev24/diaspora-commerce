package com.afrotransact.payments.port;

/** Keyed hash of gift card codes (HMAC with a secret from the environment), so the codes are never stored. */
public interface GiftCodeHasher {

    /** Hex digest of a normalised code. */
    String hash(String normalisedCode);
}
