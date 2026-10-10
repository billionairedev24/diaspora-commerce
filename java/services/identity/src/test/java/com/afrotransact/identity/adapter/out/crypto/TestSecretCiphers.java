package com.afrotransact.identity.adapter.out.crypto;

import com.afrotransact.identity.port.SecretCipher;

/** Builds the production envelope cipher over a local key ring, for tests outside this package. */
public final class TestSecretCiphers {

    private TestSecretCiphers() {
    }

    public static SecretCipher local(String keyId, String base64Key, String previousKeys) {
        return new EnvelopeSecretCipher(new LocalDataKeyWrapper(
                new DataProtectionSettings(keyId, base64Key, previousKeys)));
    }

    /** Additional authenticated data the persistence adapter uses for a user's TOTP secret. */
    public static String totpContext(Object userId) {
        return "mfa_totp.secret:" + userId;
    }
}
