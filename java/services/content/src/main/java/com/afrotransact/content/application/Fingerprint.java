package com.afrotransact.content.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Short SHA-256 fingerprints used as ETags. */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(Object... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Object p : parts) {
                sha.update(String.valueOf(p).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(sha.digest(), 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
