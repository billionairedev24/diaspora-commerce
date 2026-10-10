package com.afrotransact.identity.port;

/**
 * Encryption of secrets kept at rest (TOTP seeds). Implementations use envelope encryption: every record gets its own
 * random data key and nonce (AES-256-GCM), the data key is wrapped by a key-encryption key (local key ring or a cloud
 * KMS), and the envelope names that key so keys can rotate. A database dump alone cannot be decrypted.
 *
 * <p>{@code context} binds a ciphertext to the record it belongs to (for example {@code mfa_totp:<userId>}); opening
 * it under another context fails, so a ciphertext copied onto another account's row is useless.</p>
 */
public interface SecretCipher {

    /** Key id new envelopes are sealed under. */
    String currentKeyId();

    /** Encrypts {@code plaintext} for the record identified by {@code context}. */
    Sealed seal(String plaintext, String context);

    /**
     * Decrypts an envelope produced by {@link #seal}.
     *
     * @throws SecretCipherException when the key is unknown, the context differs or any byte was altered
     */
    String open(byte[] envelope, String context);

    /**
     * Key id an envelope was sealed under, read from its header without decrypting.
     *
     * @throws SecretCipherException when the envelope is malformed
     */
    String keyIdOf(byte[] envelope);

    /** An envelope and the id of the key-encryption key it was sealed under (also stored in its own column). */
    record Sealed(String keyId, byte[] envelope) {
    }
}
