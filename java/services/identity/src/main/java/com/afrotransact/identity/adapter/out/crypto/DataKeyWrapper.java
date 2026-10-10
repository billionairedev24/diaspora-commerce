package com.afrotransact.identity.adapter.out.crypto;

/**
 * Wraps and unwraps per-record data keys with a key-encryption key: the seam between the envelope cipher and the key
 * store. The local key ring ({@code at.identity.data-protection.provider=local}, the default) keeps AES-256 keys from
 * the secret store in memory; a cloud adapter ({@code aws-kms}, {@code gcp-kms}, {@code azure-key-vault}) implements
 * the same three methods with the KMS Encrypt/Decrypt (or WrapKey/UnwrapKey) calls, passing {@code context} as the
 * encryption context / additional authenticated data, so the key never leaves the KMS.
 */
public interface DataKeyWrapper {

    /** Id of the key new data keys are wrapped with (stored in every envelope; a KMS key version or ARN). */
    String currentKeyId();

    /** Wraps {@code dataKey} with the current key, bound to {@code context}. */
    byte[] wrap(byte[] dataKey, String context);

    /**
     * Unwraps a data key wrapped by {@code keyId}.
     *
     * @throws com.afrotransact.identity.port.SecretCipherException when the key is unknown or the data was altered
     */
    byte[] unwrap(String keyId, byte[] wrapped, String context);
}
