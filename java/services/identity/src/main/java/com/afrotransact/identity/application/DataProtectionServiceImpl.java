package com.afrotransact.identity.application;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;

import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.EncryptedSecretRepository;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SecretCipherException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Re-seals rows one by one with an optimistic compare-and-set (no long transaction, no table lock), so rotation runs
 * while users sign in. Rows are walked in user-id order; a row that cannot be opened is counted and left as it is.
 */
@Service
class DataProtectionServiceImpl implements DataProtectionService {

    private static final Logger LOG = LoggerFactory.getLogger(DataProtectionServiceImpl.class);
    private static final int BATCH = 200;

    private final CurrentUser current;
    private final SecretCipher cipher;
    private final EncryptedSecretRepository secrets;
    private final AccessChangeLog changeLog;

    DataProtectionServiceImpl(CurrentUser current, SecretCipher cipher, EncryptedSecretRepository secrets,
                              AccessChangeLog changeLog) {
        this.current = current;
        this.cipher = cipher;
        this.secrets = secrets;
        this.changeLog = changeLog;
    }

    @Override
    public DataProtectionStatus status() {
        requireSuperAdmin();
        return new DataProtectionStatus(cipher.currentKeyId(), sealedByKey());
    }

    @Override
    public ResealReport rotate() {
        Actor actor = requireSuperAdmin();
        String keyId = cipher.currentKeyId();
        ResealReport totp = reseal((after, limit) -> secrets.totpSecretsNotSealedUnder(keyId, after, limit));
        // The active signing key's private half is sealed with the same keys: leaving it under a key that is then
        // removed would stop identity from signing any token.
        int resealed = totp.resealed();
        int failed = totp.failed();
        try {
            if (secrets.resealActiveSigningKey()) {
                resealed++;
            }
        } catch (SecretCipherException ex) {
            failed++;
            LOG.warn("The active signing key cannot be opened and was left as it is: {}", ex.getMessage());
        }
        ResealReport report = new ResealReport(keyId, resealed, totp.skipped(), failed, sealedByKey());
        changeLog.append(actor.subject(), "data-protection.rotated", null, null, "mfa_totp.secret -> " + keyId,
                "resealed=" + report.resealed() + " skipped=" + report.skipped() + " failed=" + report.failed());
        LOG.info("Data-protection rotation to key {} by {}: {}", keyId, actor.subject(), report);
        return report;
    }

    @Override
    public ResealReport sealPlaintextSecrets() {
        ResealReport report = reseal(secrets::plaintextTotpSecrets);
        if (report.resealed() + report.skipped() + report.failed() > 0) {
            LOG.info("Sealed legacy plaintext TOTP secrets under key {}: {}", report.keyId(), report);
        }
        return report;
    }

    private ResealReport reseal(BiFunction<UUID, Integer, List<UUID>> page) {
        String keyId = cipher.currentKeyId();
        int resealed = 0;
        int skipped = 0;
        int failed = 0;
        UUID after = null;
        List<UUID> batch = page.apply(after, BATCH);
        while (!batch.isEmpty()) {
            for (UUID userId : batch) {
                try {
                    if (secrets.resealTotp(userId)) {
                        resealed++;
                    } else {
                        skipped++;
                    }
                } catch (SecretCipherException ex) {
                    failed++;
                    LOG.warn("TOTP secret of user {} cannot be opened and was left as it is: {}", userId,
                            ex.getMessage());
                }
            }
            after = batch.get(batch.size() - 1);
            batch = page.apply(after, BATCH);
        }
        return new ResealReport(keyId, resealed, skipped, failed, sealedByKey());
    }

    /** Everything sealed with a data-protection key, per key id: TOTP seeds and the active signing key. */
    private Map<String, Long> sealedByKey() {
        Map<String, Long> all = new TreeMap<>(secrets.totpSecretsByKey());
        secrets.signingKeysByKey().forEach((key, n) -> all.merge(key, n, Long::sum));
        return all;
    }

    private Actor requireSuperAdmin() {
        Actor actor = current.actor();
        if (actor.type() != PrincipalType.STAFF || !actor.roles().contains(RoleKeys.SUPER_ADMIN)) {
            throw new ForbiddenActionException("identity.super-admin-required",
                    "Only a super administrator can manage data-protection keys");
        }
        return actor;
    }
}
