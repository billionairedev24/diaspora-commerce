package com.afrotransact.identity.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maintenance view of the secrets sealed with {@link SecretCipher} (TOTP seeds in {@code mfa_totp}): which key each
 * row is under, and re-sealing a row under the current key (start-up migration of legacy plaintext rows and key
 * rotation). Re-sealing is optimistic: a row changed concurrently is left alone and reported as skipped.
 */
public interface EncryptedSecretRepository {

    /** Bucket of {@link #totpSecretsByKey()} for legacy rows that still hold the secret in plain text. */
    String PLAINTEXT = "plaintext";

    /** Number of TOTP secrets per key id; legacy plaintext rows are counted under {@link #PLAINTEXT}. */
    Map<String, Long> totpSecretsByKey();

    /** Users (ascending, after {@code after}, may be null) whose TOTP secret is still stored in plain text. */
    List<UUID> plaintextTotpSecrets(UUID after, int limit);

    /** Users (ascending, after {@code after}, may be null) whose TOTP secret is plain text or under another key. */
    List<UUID> totpSecretsNotSealedUnder(String keyId, UUID after, int limit);

    /**
     * Decrypts (or reads the legacy plain text of) one user's TOTP secret and stores it sealed under the current key,
     * clearing the plaintext column.
     *
     * @return true when the row was re-sealed; false when it vanished or changed in the meantime
     * @throws SecretCipherException when the stored envelope cannot be opened (for example its key was removed)
     */
    boolean resealTotp(UUID userId);
}
