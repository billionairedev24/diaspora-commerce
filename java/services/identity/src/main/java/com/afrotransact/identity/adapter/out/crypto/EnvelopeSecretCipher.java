package com.afrotransact.identity.adapter.out.crypto;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SecretCipherException;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption with AES-256-GCM. Every {@link #seal} draws a fresh 256-bit data key and a fresh 96-bit nonce,
 * encrypts the secret with the data key and has the {@link DataKeyWrapper} wrap the data key. Layout (version 1):
 *
 * <pre>
 * 0x01 | kidLen(1) | kid (UTF-8) | wrappedLen(2) | wrapped data key | nonce(12) | ciphertext + tag(16)
 * </pre>
 *
 * <p>The header (version, key id, wrapped data key) and the record context are authenticated, so changing the key id,
 * swapping the wrapped key or moving the envelope to another record fails the GCM tag check.</p>
 */
@Component
class EnvelopeSecretCipher implements SecretCipher {

    static final byte VERSION = 1;
    private static final String GCM = "AES/GCM/NoPadding";
    private static final int DATA_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final DataKeyWrapper keys;
    private final SecureRandom random = new SecureRandom();

    EnvelopeSecretCipher(DataKeyWrapper keys) {
        this.keys = keys;
    }

    @Override
    public String currentKeyId() {
        return keys.currentKeyId();
    }

    @Override
    public Sealed seal(String plaintext, String context) {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        random.nextBytes(dataKey);
        try {
            String keyId = keys.currentKeyId();
            byte[] kid = keyId.getBytes(StandardCharsets.UTF_8);
            byte[] wrapped = keys.wrap(dataKey, context);
            if (kid.length == 0 || kid.length > 255 || wrapped.length > 0xFFFF) {
                throw new IllegalStateException("Key id or wrapped data key too long for the envelope");
            }
            byte[] header = ByteBuffer.allocate(1 + 1 + kid.length + 2 + wrapped.length).put(VERSION)
                    .put((byte) kid.length).put(kid).putShort((short) wrapped.length).put(wrapped).array();
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(header);
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = ByteBuffer.allocate(header.length + NONCE_BYTES + ciphertext.length).put(header)
                    .put(nonce).put(ciphertext).array();
            return new Sealed(keyId, envelope);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM unavailable", ex);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public String open(byte[] envelope, String context) {
        Parsed p = parse(envelope);
        byte[] dataKey = keys.unwrap(p.keyId(), p.wrapped(), context);
        try {
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, envelope, p.headerLength(), NONCE_BYTES));
            c.updateAAD(envelope, 0, p.headerLength());
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            int offset = p.headerLength() + NONCE_BYTES;
            return new String(c.doFinal(envelope, offset, envelope.length - offset), StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            throw new SecretCipherException("Secret does not authenticate (tampered or bound to another record)", ex);
        } catch (GeneralSecurityException ex) {
            throw new SecretCipherException("Secret cannot be decrypted", ex);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public String keyIdOf(byte[] envelope) {
        return parse(envelope).keyId();
    }

    private static Parsed parse(byte[] envelope) {
        if (envelope == null || envelope.length < 2 || envelope[0] != VERSION) {
            throw new SecretCipherException("Unsupported or malformed secret envelope");
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(envelope);
            b.get();
            byte[] kid = new byte[Byte.toUnsignedInt(b.get())];
            b.get(kid);
            byte[] wrapped = new byte[Short.toUnsignedInt(b.getShort())];
            b.get(wrapped);
            int headerLength = b.position();
            if (envelope.length - headerLength < NONCE_BYTES + TAG_BITS / 8) {
                throw new SecretCipherException("Truncated secret envelope");
            }
            return new Parsed(new String(kid, StandardCharsets.UTF_8), wrapped, headerLength);
        } catch (BufferUnderflowException ex) {
            throw new SecretCipherException("Truncated secret envelope", ex);
        }
    }

    private record Parsed(String keyId, byte[] wrapped, int headerLength) {
    }
}
