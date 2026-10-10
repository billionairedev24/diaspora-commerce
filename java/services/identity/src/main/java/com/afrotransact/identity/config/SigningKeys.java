package com.afrotransact.identity.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads the RS256 signing key from a PEM (env) or generates an ephemeral one for local development and tests. */
final class SigningKeys {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeys.class);

    private SigningKeys() {
    }

    /** First key signs; the rest (previous keys) only verify. */
    static List<JWK> load(IdentityProperties.Signing signing) {
        List<JWK> keys = new ArrayList<>();
        keys.add(current(signing));
        int i = 0;
        for (String pem : signing.previousPublicKeysPem()) {
            if (pem != null && !pem.isBlank()) {
                keys.add(new RSAKey.Builder(publicKey(pem)).keyID(signing.keyId() + "-prev-" + (++i))
                        .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
            }
        }
        return keys;
    }

    private static RSAKey current(IdentityProperties.Signing signing) {
        String pem = signing.privateKeyPem();
        if (pem == null || pem.isBlank()) {
            if (!signing.generateIfMissing()) {
                throw new IllegalStateException("at.identity.signing.private-key-pem (SIGNING_KEY_PEM) is required");
            }
            LOG.warn("No SIGNING_KEY_PEM configured: generating an ephemeral RSA key (tokens die with this process)");
            KeyPair pair = generate();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate())
                    .keyID(signing.keyId()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
            RSAPublicKey publicKey = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),
                    privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(signing.keyId())
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new IllegalStateException("SIGNING_KEY_PEM must be a PKCS#8 RSA private key", ex);
        }
    }

    private static RSAPublicKey publicKey(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Previous signing keys must be X.509 RSA public keys", ex);
        }
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] der(String pem) {
        String body = pem.replace("\\n", "\n").replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
