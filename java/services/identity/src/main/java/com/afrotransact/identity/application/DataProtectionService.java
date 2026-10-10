package com.afrotransact.identity.application;

/**
 * Encryption at rest of MFA secrets: status per key, key rotation (staff with {@code platform.super_admin}) and the
 * start-up migration of legacy plaintext TOTP secrets.
 */
public interface DataProtectionService {

    /** Current key id and the number of TOTP secrets per key. Super admins only. */
    DataProtectionStatus status();

    /**
     * Re-seals every TOTP secret that is still plain text or sealed under another key with the current key, in
     * batches and without locking sign-ins. Super admins only; audited. Idempotent: run it again until
     * {@link DataProtectionStatus#secretsByKey()} lists only the current key.
     */
    ResealReport rotate();

    /** Seals legacy plaintext TOTP secrets (no caller check: run by the application at start-up). Idempotent. */
    ResealReport sealPlaintextSecrets();
}
