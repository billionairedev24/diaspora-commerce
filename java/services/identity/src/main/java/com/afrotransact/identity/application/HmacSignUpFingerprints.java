package com.afrotransact.identity.application;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 under {@code SIGNUP_FINGERPRINT_KEY}, so the digests cannot be reversed by trying every address. */
@Component
class HmacSignUpFingerprints implements SignUpFingerprints {

    private static final Pattern ADDRESS_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");
    private static final int MAX_DEVICE_ID = 200;

    private final SecretKeySpec key;

    HmacSignUpFingerprints(@Value("${at.identity.signup.fingerprint-key:identity-signup-fingerprints}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("at.identity.signup.fingerprint-key must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String device(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > MAX_DEVICE_ID) {
            return null;
        }
        return hmac("device:" + deviceId.trim());
    }

    @Override
    public String network(String clientAddress) {
        if (clientAddress == null || !ADDRESS_LITERAL.matcher(clientAddress.trim()).matches()) {
            return null;
        }
        try {
            // a literal address: no name lookup happens
            byte[] raw = InetAddress.getByName(clientAddress.trim()).getAddress();
            int keep = raw.length == 4 ? 3 : 8;
            return hmac("network:" + HexFormat.of().formatHex(Arrays.copyOf(raw, keep)));
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
