package com.afrotransact.identity.adapter.out.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.identity.port.SecretCipherException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Key ring of AES-256 key-encryption keys from the secret store ({@code IDENTITY_DATA_KEY} and, during a rotation,
 * {@code IDENTITY_DATA_KEYS_PREVIOUS}). A data key is wrapped as {@code nonce(12) | AES-GCM(kek, dataKey)} with the key
 * id and record context as additional authenticated data.
 */
@Component
@ConditionalOnProperty(prefix = "at.identity.data-protection", name = "provider", havingValue = "local",
        matchIfMissing = true)
class LocalDataKeyWrapper implements DataKeyWrapper {

    private static final String AES = "AES";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    LocalDataKeyWrapper(DataProtectionSettings settings) {
        if (settings.key().isEmpty()) {
            throw new IllegalStateException("IDENTITY_DATA_KEY is not set: at.identity.data-protection.key must hold "
                    + "a base64 AES-256 key (generate one with: openssl rand -base64 32)");
        }
        Map<String, SecretKey> ring = new LinkedHashMap<>();
        byte[] current = decode(settings.key(), "IDENTITY_DATA_KEY");
        this.currentKeyId = settings.keyId().isEmpty() ? derivedKeyId(current) : settings.keyId();
        ring.put(currentKeyId, new SecretKeySpec(current, AES));
        for (String entry : settings.previousKeys().split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int colon = e.indexOf(':');
            byte[] material = decode(colon < 0 ? e : e.substring(colon + 1).trim(), "IDENTITY_DATA_KEYS_PREVIOUS");
            String id = colon < 0 ? derivedKeyId(material) : e.substring(0, colon).trim();
            ring.putIfAbsent(id, new SecretKeySpec(material, AES));
        }
        this.keys = Collections.unmodifiableMap(ring);
    }

    @Override
    public String currentKeyId() {
        return currentKeyId;
    }

    @Override
    public byte[] wrap(byte[] dataKey, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyId), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(currentKeyId, context));
            byte[] sealed = c.doFinal(dataKey);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM unavailable", ex);
        }
    }

    @Override
    public byte[] unwrap(String keyId, byte[] wrapped, String context) {
        SecretKey kek = keys.get(keyId);
        if (kek == null) {
            throw new SecretCipherException("Unknown data-protection key id " + keyId);
        }
        if (wrapped.length <= NONCE_BYTES) {
            throw new SecretCipherException("Malformed wrapped data key");
        }
        try {
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES));
            c.updateAAD(aad(keyId, context));
            return c.doFinal(wrapped, NONCE_BYTES, wrapped.length - NONCE_BYTES);
        } catch (AEADBadTagException ex) {
            throw new SecretCipherException("Data key does not authenticate (wrong key, record or tampered)", ex);
        } catch (GeneralSecurityException ex) {
            throw new SecretCipherException("Data key cannot be unwrapped", ex);
        }
    }

    private static byte[] aad(String keyId, String context) {
        return ("afrotransact.identity.dek|" + keyId + "|" + context).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decode(String base64, String name) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(name + " is not valid base64", ex);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(name + " must be a 256-bit key (32 bytes, base64), got " + key.length
                    + " bytes");
        }
        return key;
    }

    /** {@code k-} + the first 16 hex chars of SHA-256("afrotransact.identity.kid|" + key): stable, reveals nothing. */
    static String derivedKeyId(byte[] key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("afrotransact.identity.kid|".getBytes(StandardCharsets.UTF_8));
            return "k-" + HexFormat.of().formatHex(sha.digest(key), 0, 8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
