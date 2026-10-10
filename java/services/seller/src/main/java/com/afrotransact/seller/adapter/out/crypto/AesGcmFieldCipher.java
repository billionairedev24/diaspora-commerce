package com.afrotransact.seller.adapter.out.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.seller.port.FieldCipher;

/**
 * AES-256-GCM with a random 96-bit IV per value. Output: {@code <keyId>:<base64(iv || ciphertext+tag)>}. The key id
 * is bound as additional authenticated data, and older keys can stay configured for decryption during rotation.
 */
public class AesGcmFieldCipher implements FieldCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String activeKeyId;
    private final Map<String, SecretKey> keys;

    /**
     * @param activeKeyId id written with new ciphertexts
     * @param base64Keys  key id → base64 of a 32-byte key (must include the active key)
     */
    public AesGcmFieldCipher(String activeKeyId, Map<String, String> base64Keys) {
        if (activeKeyId == null || !base64Keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("FIELD_ENCRYPTION_KEY is not configured");
        }
        this.activeKeyId = activeKeyId;
        this.keys = base64Keys.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> key(e.getValue())));
    }

    @Override
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        int sep = ciphertext.indexOf(':');
        if (sep <= 0) {
            throw new IllegalArgumentException("Not a field ciphertext");
        }
        String keyId = ciphertext.substring(0, sep);
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Unknown field-encryption key " + keyId);
        }
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext.substring(sep + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            c.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field decryption failed", e);
        }
    }

    private static SecretKey key(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException("Field-encryption keys must be 32 bytes (base64), got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
