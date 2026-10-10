package com.afrotransact.identity.domain;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA1, 6 digits, 30-second steps: the parameters every mainstream authenticator app supports. */
public class DefaultTotpAuthenticator implements TotpAuthenticator {

    private static final int SECRET_BYTES = 20;
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private final SecureRandom random = new SecureRandom();

    @Override
    public String newSecret() {
        byte[] key = new byte[SECRET_BYTES];
        random.nextBytes(key);
        return Base32.encode(key);
    }

    @Override
    public String otpauthUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    @Override
    public String codeAt(String secret, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(Base32.decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % POW10[DIGITS]);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA1 unavailable", ex);
        }
    }

    @Override
    public OptionalLong verify(String secret, String code, Instant now, long lastUsedStep) {
        if (code == null) {
            return OptionalLong.empty();
        }
        String clean = code.replace(" ", "").trim();
        if (clean.length() != DIGITS) {
            return OptionalLong.empty();
        }
        long step = now.getEpochSecond() / PERIOD_SECONDS;
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            if (candidate > lastUsedStep && SecretHashing.constantTimeEquals(codeAt(secret, candidate), clean)) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
