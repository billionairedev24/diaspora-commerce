package com.afrotransact.payments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** SHA-256 of a command's canonical form (stored with idempotency keys) and deterministic ids. */
final class Fingerprint {

    private Fingerprint() {
    }

    static String of(Object command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(command).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Deterministic id so a retried transaction re-sends Stripe exactly the same parameters. */
    static UUID id(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }
}
