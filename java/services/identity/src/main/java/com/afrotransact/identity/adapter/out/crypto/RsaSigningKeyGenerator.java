package com.afrotransact.identity.adapter.out.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import com.afrotransact.identity.port.SigningKeyGenerator;
import org.springframework.stereotype.Component;

/** RSA 2048 key pairs for RS256, the algorithm every resource server of the platform verifies. */
@Component
class RsaSigningKeyGenerator implements SigningKeyGenerator {

    private static final int KEY_BITS = 2048;

    @Override
    public Generated generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            return new Generated(Pem.publicKey((RSAPublicKey) pair.getPublic()), Pem.privateKey(pair.getPrivate()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("RSA key generation failed", ex);
        }
    }
}
