package com.afrotransact.taxreturns.domain.profile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Stable hash of a seller's behaviour facts (null values ignored, keys sorted), the key beside the variant ATIN. */
public final class BehaviourHash {

    private BehaviourHash() {
    }

    public static String of(Map<String, Object> behaviour) {
        StringBuilder canonical = new StringBuilder();
        Map<String, Object> sorted = new TreeMap<>();
        if (behaviour != null) {
            behaviour.forEach((k, v) -> {
                if (v != null) {
                    sorted.put(k, v);
                }
            });
        }
        sorted.forEach((k, v) -> canonical.append(k).append('=').append(canonicalValue(v)).append(';'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }

    private static String canonicalValue(Object v) {
        BigDecimal n = Condition.number(v);
        return n == null ? String.valueOf(v) : n.stripTrailingZeros().toPlainString();
    }
}
