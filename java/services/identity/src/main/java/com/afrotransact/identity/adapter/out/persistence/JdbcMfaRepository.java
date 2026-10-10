package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.TrustedBrowser;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.SecretCipher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * MFA tables. TOTP secrets are sealed with {@link SecretCipher} (envelope encryption, bound to the user id) and
 * stored in {@code secret_ciphertext} with their key id; the legacy plaintext column {@code secret} is always written
 * as null and only read for rows the start-up migrator has not sealed yet. Recovery codes and trusted-browser tokens
 * are high-entropy values stored as SHA-256 hashes only.
 */
@Repository
class JdbcMfaRepository implements MfaRepository {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    JdbcMfaRepository(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Additional authenticated data of a user's TOTP secret: a ciphertext only opens on its own row. */
    static String totpContext(UUID userId) {
        return "mfa_totp.secret:" + userId;
    }

    @Override
    public Optional<TotpEnrolment> totp(UUID userId) {
        return jdbc.sql("""
                select user_id, secret, secret_ciphertext, created_at, confirmed_at, last_used_step
                from mfa_totp where user_id = :u
                """).param("u", userId)
                .query((rs, n) -> {
                    byte[] sealed = rs.getBytes("secret_ciphertext");
                    String secret = sealed != null ? cipher.open(sealed, totpContext(userId)) : rs.getString("secret");
                    return new TotpEnrolment(rs.getObject("user_id", UUID.class), secret,
                            Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "confirmed_at"),
                            rs.getLong("last_used_step"));
                })
                .optional();
    }

    @Override
    public void saveTotp(TotpEnrolment e) {
        SecretCipher.Sealed sealed = cipher.seal(e.secret(), totpContext(e.userId()));
        jdbc.sql("""
                insert into mfa_totp (user_id, secret, secret_ciphertext, secret_key_id, created_at, confirmed_at,
                                      last_used_step)
                values (:u, null, :ct, :kid, :created, :confirmed, :step)
                on conflict (user_id) do update set secret = null, secret_ciphertext = excluded.secret_ciphertext,
                    secret_key_id = excluded.secret_key_id, created_at = excluded.created_at,
                    confirmed_at = excluded.confirmed_at, last_used_step = excluded.last_used_step
                """)
                .param("u", e.userId())
                .param("ct", sealed.envelope())
                .param("kid", sealed.keyId())
                .param("created", Jdbc.ts(e.createdAt()))
                .param("confirmed", Jdbc.ts(e.confirmedAt()))
                .param("step", e.lastUsedStep())
                .update();
    }

    @Override
    public void deleteTotp(UUID userId) {
        jdbc.sql("delete from mfa_totp where user_id = :u").param("u", userId).update();
    }

    @Override
    public Optional<String> smsPhone(UUID userId) {
        return jdbc.sql("select phone_e164 from mfa_sms where user_id = :u").param("u", userId)
                .query(String.class).optional();
    }

    @Override
    public void enableSms(UUID userId, String phone, Instant at) {
        jdbc.sql("""
                insert into mfa_sms (user_id, phone_e164, enabled_at) values (:u, :p, :at)
                on conflict (user_id) do update set phone_e164 = excluded.phone_e164, enabled_at = excluded.enabled_at
                """).param("u", userId).param("p", phone).param("at", Jdbc.ts(at)).update();
    }

    @Override
    public void disableSms(UUID userId) {
        jdbc.sql("delete from mfa_sms where user_id = :u").param("u", userId).update();
    }

    @Override
    public void replaceRecoveryCodes(UUID userId, List<String> hashes, Instant at) {
        deleteRecoveryCodes(userId);
        for (String hash : hashes) {
            jdbc.sql("insert into mfa_recovery_code (user_id, code_hash, created_at) values (:u, :h, :at)")
                    .param("u", userId).param("h", hash).param("at", Jdbc.ts(at)).update();
        }
    }

    @Override
    public int unusedRecoveryCodes(UUID userId) {
        return jdbc.sql("select count(*) from mfa_recovery_code where user_id = :u and used_at is null")
                .param("u", userId).query(Integer.class).single();
    }

    @Override
    public boolean useRecoveryCode(UUID userId, String hash, Instant at) {
        return jdbc.sql("""
                update mfa_recovery_code set used_at = :at where user_id = :u and code_hash = :h and used_at is null
                """).param("u", userId).param("h", hash).param("at", Jdbc.ts(at)).update() == 1;
    }

    @Override
    public void deleteRecoveryCodes(UUID userId) {
        jdbc.sql("delete from mfa_recovery_code where user_id = :u").param("u", userId).update();
    }

    @Override
    public void insertTrustedBrowser(TrustedBrowser b) {
        jdbc.sql("""
                insert into trusted_browser (id, user_id, token_hash, user_agent, created_at, expires_at)
                values (:id, :u, :h, :ua, :created, :expires)
                """)
                .param("id", b.id()).param("u", b.userId()).param("h", b.tokenHash()).param("ua", b.userAgent())
                .param("created", Jdbc.ts(b.createdAt())).param("expires", Jdbc.ts(b.expiresAt()))
                .update();
    }

    @Override
    public Optional<TrustedBrowser> trustedBrowser(UUID userId, String tokenHash, Instant now) {
        return jdbc.sql("""
                select * from trusted_browser where user_id = :u and token_hash = :h and expires_at > :now
                """)
                .param("u", userId).param("h", tokenHash).param("now", Jdbc.ts(now))
                .query((rs, n) -> new TrustedBrowser(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("token_hash"), rs.getString("user_agent"),
                        Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at")))
                .optional();
    }

    @Override
    public void deleteTrustedBrowsers(UUID userId) {
        jdbc.sql("delete from trusted_browser where user_id = :u").param("u", userId).update();
    }
}
