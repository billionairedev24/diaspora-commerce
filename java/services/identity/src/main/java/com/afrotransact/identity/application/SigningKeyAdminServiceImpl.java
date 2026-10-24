package com.afrotransact.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.AdminReason;
import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.domain.SigningKeyStatus;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SigningKeyGenerator;
import com.afrotransact.identity.port.SigningKeyStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SigningKeyAdminServiceImpl implements SigningKeyAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeyAdminServiceImpl.class);
    private static final String ALGORITHM = "RS256";
    private static final DateTimeFormatter KID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SigningKeyStore keys;
    private final SigningKeyGenerator generator;
    private final SecretCipher cipher;
    private final OAuthClientAdminStore clients;
    private final AuthSettingsService settings;
    private final PermissionChecker permissions;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    SigningKeyAdminServiceImpl(SigningKeyStore keys, SigningKeyGenerator generator, SecretCipher cipher,
                               OAuthClientAdminStore clients, AuthSettingsService settings,
                               PermissionChecker permissions, AccessChangeLog changeLog, Clock clock) {
        this.keys = keys;
        this.generator = generator;
        this.cipher = cipher;
        this.clients = clients;
        this.settings = settings;
        this.permissions = permissions;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<SigningKey> list() {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        retireExpired(Instant.now(clock));
        return keys.all();
    }

    @Override
    @Transactional
    public SigningKey rotate(String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        Instant now = Instant.now(clock);
        retireExpired(now);
        Optional<SigningKey> active = keys.all().stream().filter(k -> k.status() == SigningKeyStatus.ACTIVE)
                .findFirst();
        Instant retireAfter = SigningKey.retireAfter(now, longestAccessToken());
        active.ifPresent(k -> keys.markRetiring(k.kid(), now, retireAfter));

        SigningKeyGenerator.Generated pair = generator.generate();
        String kid = nextKid(now);
        SecretCipher.Sealed sealed = cipher.seal(pair.privateKeyPem(), SigningKey.sealContext(kid));
        SigningKey key = new SigningKey(kid, ALGORITHM, SigningKeyStatus.ACTIVE, pair.publicKeyPem(), now, null, null,
                null);
        keys.insertActive(key, sealed.envelope(), sealed.keyId(), actor.subject());
        changeLog.append(actor.subject(), "signing-key.rotated", null, null, kid,
                "reason=" + why + "; previous=" + active.map(SigningKey::kid).orElse("none")
                        + active.map(k -> " retires after " + retireAfter).orElse(""));
        LOG.info("Signing key rotated by {}: {} is active, {} retires after {}", actor.subject(), kid,
                active.map(SigningKey::kid).orElse("(none)"), retireAfter);
        return key;
    }

    @Override
    @Transactional
    public void seedIfEmpty(String kid, String publicKeyPem, String privateKeyPem) {
        if (!keys.all().isEmpty()) {
            return;
        }
        SecretCipher.Sealed sealed = cipher.seal(privateKeyPem, SigningKey.sealContext(kid));
        SigningKey key = new SigningKey(kid, ALGORITHM, SigningKeyStatus.ACTIVE, publicKeyPem, Instant.now(clock),
                null, null, null);
        if (keys.insertActive(key, sealed.envelope(), sealed.keyId(), "system")) {
            LOG.info("Configured signing key {} stored as the active key", kid);
        }
    }

    private void retireExpired(Instant now) {
        for (SigningKey k : keys.all()) {
            if (k.status() == SigningKeyStatus.RETIRING && k.statusAt(now) == SigningKeyStatus.RETIRED) {
                keys.markRetired(k.kid(), now);
            }
        }
    }

    /** The longest an access token issued under the outgoing key can still be valid. */
    private Duration longestAccessToken() {
        AuthSettings s = settings.effective();
        return clients.all().stream().map(OAuthClient::accessTokenTtl).map(s::capAccess).max(Duration::compareTo)
                .orElse(Duration.ofHours(1));
    }

    private static String nextKid(Instant now) {
        byte[] r = new byte[2];
        RANDOM.nextBytes(r);
        return "k" + KID_TIME.format(now) + "-" + HexFormat.of().formatHex(r);
    }
}
