package com.afrotransact.identity.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.domain.SigningKeyStatus;
import com.afrotransact.identity.domain.TokenInspection;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SigningKeyGenerator;
import com.afrotransact.identity.port.SigningKeyStore;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

/** Stored signing keys: what signs, what is published, and what the token inspector says about tokens. */
class SigningKeysAndInspectorTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String ISSUER = "https://id.afrotransact.test";
    private static final JWKSelector ALL = new JWKSelector(new JWKMatcher.Builder().build());

    private final SecretCipher cipher = TestSecretCiphers.local("unit-1",
            "mB3FPeNA2n3gpPjBDhziub681AZitNXX4hoUn2t/IQ0=", "");
    private final SigningKeyGenerator generator = new RsaSigningKeyGenerator();
    private final MemoryStore store = new MemoryStore();
    private final StoredJwkSource source = new StoredJwkSource(store, cipher, List.of(), Clock.fixed(NOW,
            ZoneOffset.UTC));

    /** Stores a generated key the way the rotation service does. */
    private SigningKeyGenerator.Generated store(String kid, SigningKeyStatus status, Instant retireAfter) {
        SigningKeyGenerator.Generated pair = generator.generate();
        store.keys.add(new SigningKey(kid, "RS256", status, pair.publicKeyPem(), NOW.minusSeconds(3600), null,
                retireAfter, null));
        if (status == SigningKeyStatus.ACTIVE) {
            store.sealed.put(kid, cipher.seal(pair.privateKeyPem(), SigningKey.sealContext(kid)).envelope());
        }
        return pair;
    }

    private static String sign(SigningKeyGenerator.Generated pair, String kid, String issuer, Instant expires)
            throws JOSEException {
        return sign(pair, kid, issuer, expires, "openid profile");
    }

    private static String sign(SigningKeyGenerator.Generated pair, String kid, String issuer, Instant expires,
                               Object scopeClaim)
            throws JOSEException {
        RSAKey key = new RSAKey.Builder(Pem.rsaPublic(pair.publicKeyPem())).privateKey(Pem.rsaPrivate(pair.privateKeyPem()))
                .keyID(kid).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject("user-1").issueTime(Date.from(NOW.minusSeconds(60)))
                        .expirationTime(Date.from(expires)).claim("scope", scopeClaim)
                        .claim("perms", List.of("identity:admin")).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void theActiveKeySignsAndRetiringKeysStayPublishedWithoutTheirPrivatePart() throws Exception {
        store("active", SigningKeyStatus.ACTIVE, null);
        store("retiring", SigningKeyStatus.RETIRING, NOW.plusSeconds(120));
        store("retired", SigningKeyStatus.RETIRING, NOW.minusSeconds(1));

        var keys = source.get(ALL, null);

        assertThat(keys).extracting(k -> k.getKeyID()).containsExactlyInAnyOrder("active", "retiring");
        assertThat(keys).filteredOn(JWK::isPrivate).extracting(k -> k.getKeyID()).containsExactly("active");
    }

    @Test
    void thePublishedJwksNeverHoldPrivateKeyMaterial() throws Exception {
        store("active", SigningKeyStatus.ACTIVE, null);
        Map<String, Object> jwks = new PublishedKeys(source, null, List.of()).jwks();
        assertThat(jwks.toString()).doesNotContain("\"d\"").doesNotContain("=d,").doesNotContain("PRIVATE");
        assertThat(new JWKSet(source.get(ALL, null)).toJSONObject(true).toString()).doesNotContain("\"d\"");
        assertThat(JWKSet.parse(jwks).getKeys()).allMatch(k -> !k.isPrivate());
    }

    @Test
    void aValidTokenVerifiesAgainstThePublishedKey() throws Exception {
        var pair = store("active", SigningKeyStatus.ACTIVE, null);
        TokenInspection t = new NimbusJwtInspector(source).inspect(sign(pair, "active", ISSUER,
                NOW.plusSeconds(300)), ISSUER, NOW);

        assertThat(t.valid()).isTrue();
        assertThat(t.signatureValid()).isTrue();
        assertThat(t.issuerValid()).isTrue();
        assertThat(t.expired()).isFalse();
        assertThat(t.keyId()).isEqualTo("active");
        assertThat(t.scopes()).containsExactly("openid", "profile");
        assertThat(t.claims()).containsEntry("sub", "user-1");
        assertThat(t.header()).containsEntry("kid", "active");
        assertThat(t.problems()).isEmpty();
        assertThat(t.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void scopesReadAsAnArrayLikeSpringAuthorizationServerWritesThemOrAsAString() throws Exception {
        var pair = store("active", SigningKeyStatus.ACTIVE, null);
        TokenInspection array = new NimbusJwtInspector(source).inspect(sign(pair, "active", ISSUER,
                NOW.plusSeconds(60), List.of("openid", "phone")), ISSUER, NOW);
        assertThat(array.scopes()).containsExactly("openid", "phone");
    }

    @Test
    void aTokenSignedByARetiringKeyStillVerifiesUntilTheKeyIsRetired() throws Exception {
        var pair = store("old", SigningKeyStatus.RETIRING, NOW.plusSeconds(120));
        store("active", SigningKeyStatus.ACTIVE, null);
        String token = sign(pair, "old", ISSUER, NOW.plusSeconds(60));
        assertThat(new NimbusJwtInspector(source).inspect(token, ISSUER, NOW).valid()).isTrue();

        store.keys.replaceAll(k -> k.kid().equals("old")
                ? new SigningKey("old", "RS256", SigningKeyStatus.RETIRING, k.publicKeyPem(), k.createdAt(), null,
                        NOW.minusSeconds(1), null) : k);
        source.invalidate();
        TokenInspection gone = new NimbusJwtInspector(source).inspect(token, ISSUER, NOW);
        assertThat(gone.signatureValid()).isFalse();
        assertThat(gone.problems()).anyMatch(p -> p.contains("No published key has the id old"));
    }

    @Test
    void expiredWrongIssuerAndTamperedTokensAreReportedNotThrown() throws Exception {
        var pair = store("active", SigningKeyStatus.ACTIVE, null);
        NimbusJwtInspector inspector = new NimbusJwtInspector(source);

        TokenInspection expired = inspector.inspect(sign(pair, "active", ISSUER, NOW.minus(Duration.ofMinutes(1))),
                ISSUER, NOW);
        assertThat(expired.valid()).isFalse();
        assertThat(expired.signatureValid()).isTrue();
        assertThat(expired.expired()).isTrue();

        TokenInspection foreign = inspector.inspect(sign(pair, "active", "https://evil.example", NOW.plusSeconds(60)),
                ISSUER, NOW);
        assertThat(foreign.valid()).isFalse();
        assertThat(foreign.issuerValid()).isFalse();

        String good = sign(pair, "active", ISSUER, NOW.plusSeconds(60));
        String[] parts = good.split("\\.");
        String forgedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"%s\",\"sub\":\"admin\",\"exp\":%d}".formatted(ISSUER, NOW.plusSeconds(60).getEpochSecond())
                        .getBytes(StandardCharsets.UTF_8));
        TokenInspection tampered = inspector.inspect(parts[0] + "." + forgedClaims + "." + parts[2], ISSUER, NOW);
        assertThat(tampered.valid()).isFalse();
        assertThat(tampered.signatureValid()).isFalse();
    }

    @Test
    void whatIsNotAJwtIsMalformedAndNothingIsEchoed() {
        TokenInspection t = new NimbusJwtInspector(source).inspect("opaque-refresh-token-value", ISSUER, NOW);
        assertThat(t.wellFormed()).isFalse();
        assertThat(t.valid()).isFalse();
        assertThat(t.toString()).doesNotContain("opaque-refresh-token-value");
        assertThat(t.problems()).hasSize(1);
    }

    @Test
    void untilAKeyIsStoredTheConfiguredKeyIsUsed() throws Exception {
        var pair = generator.generate();
        RSAKey configured = new RSAKey.Builder(Pem.rsaPublic(pair.publicKeyPem()))
                .privateKey(Pem.rsaPrivate(pair.privateKeyPem())).keyID("identity-1").build();
        StoredJwkSource fallback = new StoredJwkSource(new MemoryStore(), cipher, List.of(configured),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(fallback.get(ALL, null)).extracting(k -> k.getKeyID()).containsExactly("identity-1");
    }

    private static final class MemoryStore implements SigningKeyStore {
        final List<SigningKey> keys = new ArrayList<>();
        final Map<String, byte[]> sealed = new HashMap<>();

        @Override
        public List<SigningKey> all() {
            return List.copyOf(keys);
        }

        @Override
        public Optional<byte[]> sealedPrivateKey(String kid) {
            return Optional.ofNullable(sealed.get(kid));
        }

        @Override
        public boolean insertActive(SigningKey key, byte[] sealedPrivateKey, String sealKeyId, String by) {
            keys.add(key);
            sealed.put(key.kid(), sealedPrivateKey);
            return true;
        }

        @Override
        public void markRetiring(String kid, Instant rotatedAt, Instant retireAfter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markRetired(String kid, Instant at) {
            throw new UnsupportedOperationException();
        }
    }
}
