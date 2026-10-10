package com.afrotransact.identity.application;

import java.util.Map;

/**
 * Outcome of a re-seal run.
 *
 * @param keyId     key the secrets were sealed under
 * @param resealed  rows re-encrypted
 * @param skipped   rows that changed or vanished meanwhile (already written under the current key by a sign-in)
 * @param failed    rows that could not be opened (key missing from the key ring); they keep their old ciphertext
 * @param remaining TOTP secrets per key id after the run
 */
public record ResealReport(String keyId, int resealed, int skipped, int failed, Map<String, Long> remaining) {

    public ResealReport {
        remaining = Map.copyOf(remaining);
    }
}
