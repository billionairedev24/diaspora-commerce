package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.SigningKey;

/** Signing keys. The private half exists only for the ACTIVE key, sealed with the data-protection cipher. */
public interface SigningKeyStore {

    /** Every key, newest first. */
    List<SigningKey> all();

    Optional<byte[]> sealedPrivateKey(String kid);

    /** Adds the active key; false when a key with this id already exists (another replica seeded it first). */
    boolean insertActive(SigningKey key, byte[] sealedPrivateKey, String sealKeyId, String by);

    /** ACTIVE to RETIRING: the private half is erased. */
    void markRetiring(String kid, Instant rotatedAt, Instant retireAfter);

    void markRetired(String kid, Instant at);
}
