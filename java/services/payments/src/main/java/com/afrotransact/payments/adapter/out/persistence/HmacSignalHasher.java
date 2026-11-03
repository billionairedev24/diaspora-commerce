package com.afrotransact.payments.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.payments.port.SignalHasher;

/** HMAC-SHA256 of a normalised value under {@code at.payments.referral.signal-key}; the values are never stored. */
public class HmacSignalHasher implements SignalHasher {

    private final SecretKeySpec key;

    public HmacSignalHasher(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("at.payments.referral.signal-key must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String hash(String normalised) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
