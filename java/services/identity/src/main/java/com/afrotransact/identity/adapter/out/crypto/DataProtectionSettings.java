package com.afrotransact.identity.adapter.out.crypto;

/**
 * Key-encryption key configuration for the local key ring ({@code at.identity.data-protection.*}).
 *
 * @param keyId        id of the current key; blank → derived from the key itself ({@code k-} + 16 hex chars)
 * @param key          current AES-256 key, base64 (env {@code IDENTITY_DATA_KEY}); new secrets are sealed under it
 * @param previousKeys retired keys still able to open existing envelopes until they are rotated: comma-separated
 *                     {@code base64} or {@code keyId:base64} entries (env {@code IDENTITY_DATA_KEYS_PREVIOUS})
 */
public record DataProtectionSettings(String keyId, String key, String previousKeys) {

    public DataProtectionSettings {
        keyId = keyId == null ? "" : keyId.trim();
        key = key == null ? "" : key.trim();
        previousKeys = previousKeys == null ? "" : previousKeys.trim();
    }

    /** Never prints key material. */
    @Override
    public String toString() {
        return "DataProtectionSettings[keyId=" + keyId + ", key=" + (key.isEmpty() ? "<unset>" : "<redacted>")
                + ", previousKeys=" + (previousKeys.isEmpty() ? "<none>" : "<redacted>") + "]";
    }
}
