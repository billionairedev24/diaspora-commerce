package com.afrotransact.identity.application;

import java.util.Map;

/**
 * @param currentKeyId key new secrets are sealed under
 * @param secretsByKey TOTP secrets per key id ({@code plaintext} = legacy rows not migrated yet)
 */
public record DataProtectionStatus(String currentKeyId, Map<String, Long> secretsByKey) {

    public DataProtectionStatus {
        secretsByKey = Map.copyOf(secretsByKey);
    }
}
