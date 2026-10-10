package com.afrotransact.content.adapter.out.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.content.application.ContentSettings;
import com.afrotransact.content.domain.newsletter.TokenPurpose;
import com.afrotransact.content.port.NewsletterTokens;
import org.springframework.stereotype.Component;

/**
 * Stateless signed links: {@code base64url("v1|id|purpose|expEpochSeconds") + "." + base64url(HMAC-SHA256)}.
 * The key comes from {@code NEWSLETTER_TOKEN_KEY}; rotating it invalidates outstanding links.
 */
@Component
class HmacNewsletterTokens implements NewsletterTokens {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    HmacNewsletterTokens(ContentSettings settings) {
        String secret = settings.newsletterTokenKey();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("at.content.newsletter.token-key (NEWSLETTER_TOKEN_KEY) must be at least 32 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String issue(UUID subscriptionId, TokenPurpose purpose, Instant expiresAt) {
        byte[] payload = ("v1|" + subscriptionId + "|" + purpose.name() + "|" + expiresAt.getEpochSecond())
                .getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    @Override
    public Optional<Claims> verify(String token, Instant now) {
        if (token == null || token.length() > 512) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), signature)) {
                return Optional.empty();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 4 || !"v1".equals(parts[0])) {
                return Optional.empty();
            }
            Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[3]));
            if (!now.isBefore(expires)) {
                return Optional.empty();
            }
            return Optional.of(new Claims(UUID.fromString(parts[1]), TokenPurpose.valueOf(parts[2]), expires));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }
}
