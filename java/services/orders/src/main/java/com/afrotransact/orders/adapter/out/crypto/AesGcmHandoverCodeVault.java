package com.afrotransact.orders.adapter.out.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.orders.port.HandoverCodeVault;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM sealing of handover codes, bound to the shipment id (AAD). The key is derived (SHA-256) from
 * {@code at.orders.handover.key} (HANDOVER_CODE_KEY in cloud), so a database dump alone never reveals codes.
 */
@Component
class AesGcmHandoverCodeVault implements HandoverCodeVault {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    AesGcmHandoverCodeVault(@Value("${at.orders.handover.key}") String secret) throws GeneralSecurityException {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("at.orders.handover.key must be at least 16 characters");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(digest, "AES");
    }

    @Override
    public String seal(String shipmentId, String code) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(shipmentId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal handover code", e);
        }
    }

    @Override
    public String reveal(String shipmentId, String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed.substring(sealed.indexOf(':') + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(shipmentId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not reveal handover code of shipment " + shipmentId, e);
        }
    }
}
