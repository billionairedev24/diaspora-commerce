package com.afrotransact.orders.adapter.out.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.orders.port.PayLinkSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 pay-now link tokens: {@code v1.<expiry epoch seconds>.<base64url mac>} over
 * {@code paylink.v1|<orderId>|<orderNumber>|<expiry>}. The key comes from {@code at.orders.pay-links.key}
 * (PAY_LINK_KEY in cloud; never a default outside local). Comparison is constant-time.
 */
@Component
class HmacPayLinkSigner implements PayLinkSigner {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecretKeySpec key;

    HmacPayLinkSigner(@Value("${at.orders.pay-links.key}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("at.orders.pay-links.key must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String sign(String orderId, String orderNumber, Instant expiresAt) {
        long exp = expiresAt.getEpochSecond();
        return VERSION + "." + exp + "." + mac(orderId, orderNumber, exp);
    }

    @Override
    public Optional<Instant> verify(String orderId, String orderNumber, String token) {
        if (token == null || token.length() > 200) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        long exp;
        try {
            exp = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        byte[] expected = mac(orderId, orderNumber, exp).getBytes(StandardCharsets.US_ASCII);
        byte[] given = parts[2].getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? Optional.of(Instant.ofEpochSecond(exp)) : Optional.empty();
    }

    private String mac(String orderId, String orderNumber, long exp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] sig = mac.doFinal(("paylink." + VERSION + "|" + orderId + "|" + orderNumber + "|" + exp)
                    .getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
