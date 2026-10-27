package com.afrotransact.payments.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.payments.port.GiftCodeHasher;

/** HMAC-SHA256 of the normalised code under the {@code GIFT_CARD_CODE_KEY} secret (never stored with the hashes). */
public class HmacGiftCodeHasher implements GiftCodeHasher {

    private final SecretKeySpec key;

    public HmacGiftCodeHasher(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("at.payments.gift-cards.code-key must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String hash(String normalisedCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalisedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
