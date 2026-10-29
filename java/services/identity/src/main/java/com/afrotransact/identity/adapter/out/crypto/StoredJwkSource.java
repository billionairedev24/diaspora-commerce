package com.afrotransact.identity.adapter.out.crypto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.domain.SigningKeyStatus;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SecretCipherException;
import com.afrotransact.identity.port.SigningKeyCache;
import com.afrotransact.identity.port.SigningKeyStore;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The keys the authorization server signs with and publishes at {@code /oauth2/jwks}, read from the signing-key table:
 * the ACTIVE key signs (its private half is opened with the data-protection cipher), RETIRING keys stay published
 * (public half only) until their retirement date. Until a key is stored the configured key is used, and keys listed as
 * previous public keys in the configuration stay published. The table is re-read every few seconds, so a rotation on
 * one replica reaches the others quickly; both signing and the JWKS come from the same snapshot.
 */
public class StoredJwkSource implements JWKSource<SecurityContext>, SigningKeyCache {

    private static final Logger LOG = LoggerFactory.getLogger(StoredJwkSource.class);
    private static final Duration REFRESH = Duration.ofSeconds(5);

    private final SigningKeyStore store;
    private final SecretCipher cipher;
    private final List<JWK> configured;
    private final Clock clock;
    private volatile Snapshot snapshot;

    /** @param configured the configured signing key first, then previous public keys (see SigningKeys) */
    public StoredJwkSource(SigningKeyStore store, SecretCipher cipher, List<JWK> configured, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.configured = List.copyOf(configured);
        this.clock = clock;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        return selector.select(current());
    }

    /** Drops the snapshot so the next use reads the table (after a rotation on this replica). */
    @Override
    public void invalidate() {
        snapshot = null;
    }

    private JWKSet current() {
        Instant now = Instant.now(clock);
        Snapshot s = snapshot;
        if (s == null || s.at().plus(REFRESH).isBefore(now)) {
            s = new Snapshot(now, load(now));
            snapshot = s;
        }
        return s.keys();
    }

    private JWKSet load(Instant now) {
        List<SigningKey> stored = store.all();
        if (stored.stream().noneMatch(k -> k.status() == SigningKeyStatus.ACTIVE)) {
            return new JWKSet(configured);
        }
        List<JWK> keys = new ArrayList<>();
        for (SigningKey k : stored) {
            if (!k.publishedAt(now)) {
                continue;
            }
            keys.add(k.status() == SigningKeyStatus.ACTIVE ? signingKey(k) : publicOnly(k));
        }
        configured.stream().skip(1).forEach(keys::add);
        return new JWKSet(keys);
    }

    private RSAKey signingKey(SigningKey k) {
        Optional<byte[]> sealed = store.sealedPrivateKey(k.kid());
        try {
            if (sealed.isPresent()) {
                String privatePem = cipher.open(sealed.get(), SigningKey.sealContext(k.kid()));
                return new RSAKey.Builder(Pem.rsaPublic(k.publicKeyPem())).privateKey(Pem.rsaPrivate(privatePem))
                        .keyID(k.kid()).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
            }
        } catch (SecretCipherException ex) {
            LOG.error("The private half of signing key {} cannot be opened ({}); it is published but cannot sign",
                    k.kid(), ex.getMessage());
        }
        return publicOnly(k);
    }

    private static RSAKey publicOnly(SigningKey k) {
        return new RSAKey.Builder(Pem.rsaPublic(k.publicKeyPem())).keyID(k.kid()).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).build();
    }

    private record Snapshot(Instant at, JWKSet keys) {
    }
}
