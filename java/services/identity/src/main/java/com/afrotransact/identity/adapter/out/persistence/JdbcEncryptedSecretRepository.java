package com.afrotransact.identity.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.port.EncryptedSecretRepository;
import com.afrotransact.identity.port.SecretCipher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEncryptedSecretRepository implements EncryptedSecretRepository {

    /** The nil UUID sorts first in PostgreSQL's byte-wise uuid order (account ids are random v4 UUIDs). */
    private static final UUID FIRST = new UUID(0L, 0L);

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    JdbcEncryptedSecretRepository(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public Map<String, Long> totpSecretsByKey() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("""
                select case when secret_ciphertext is null then :plain else secret_key_id end as k, count(*) as n
                from mfa_totp group by 1 order by 1
                """).param("plain", PLAINTEXT)
                .query((rs, i) -> Map.entry(rs.getString("k"), rs.getLong("n"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public List<UUID> plaintextTotpSecrets(UUID after, int limit) {
        return jdbc.sql("""
                select user_id from mfa_totp where secret_ciphertext is null and user_id > :after
                order by user_id limit :n
                """).param("after", after == null ? FIRST : after).param("n", limit).query(UUID.class).list();
    }

    @Override
    public List<UUID> totpSecretsNotSealedUnder(String keyId, UUID after, int limit) {
        return jdbc.sql("""
                select user_id from mfa_totp
                where (secret_ciphertext is null or secret_key_id is distinct from :k) and user_id > :after
                order by user_id limit :n
                """).param("k", keyId).param("after", after == null ? FIRST : after).param("n", limit)
                .query(UUID.class).list();
    }

    @Override
    public boolean resealTotp(UUID userId) {
        Optional<StoredSecret> row = jdbc.sql("select secret, secret_ciphertext from mfa_totp where user_id = :u")
                .param("u", userId)
                .query((rs, n) -> new StoredSecret(rs.getString("secret"), rs.getBytes("secret_ciphertext")))
                .optional();
        if (row.isEmpty()) {
            return false;
        }
        StoredSecret old = row.get();
        String context = JdbcMfaRepository.totpContext(userId);
        String secret = old.ciphertext() != null ? cipher.open(old.ciphertext(), context) : old.plaintext();
        if (secret == null) {
            return false;
        }
        SecretCipher.Sealed sealed = cipher.seal(secret, context);
        // Compare-and-set on the value we read, so a concurrent enrolment or sign-in is never overwritten.
        String guard = old.ciphertext() != null ? "secret_ciphertext = :old"
                : "secret_ciphertext is null and secret = :old";
        return jdbc.sql("update mfa_totp set secret = null, secret_ciphertext = :ct, secret_key_id = :kid "
                        + "where user_id = :u and " + guard)
                .param("ct", sealed.envelope()).param("kid", sealed.keyId()).param("u", userId)
                .param("old", old.ciphertext() != null ? old.ciphertext() : old.plaintext())
                .update() == 1;
    }

    @Override
    public Map<String, Long> signingKeysByKey() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("select seal_key_id as k, count(*) as n from signing_key where private_key_sealed is not null "
                + "group by 1 order by 1")
                .query((rs, i) -> Map.entry(String.valueOf(rs.getString("k")), rs.getLong("n"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public boolean resealActiveSigningKey() {
        Optional<SealedKey> row = jdbc.sql("select kid, private_key_sealed from signing_key "
                + "where status = 'ACTIVE' and private_key_sealed is not null")
                .query((rs, n) -> new SealedKey(rs.getString("kid"), rs.getBytes("private_key_sealed"))).optional();
        if (row.isEmpty() || cipher.keyIdOf(row.get().envelope()).equals(cipher.currentKeyId())) {
            return false;
        }
        String context = SigningKey.sealContext(row.get().kid());
        SecretCipher.Sealed sealed = cipher.seal(cipher.open(row.get().envelope(), context), context);
        // Compare-and-set on the value we read: a rotation that retired this key meanwhile is never overwritten.
        return jdbc.sql("update signing_key set private_key_sealed = :new, seal_key_id = :kid "
                + "where kid = :k and status = 'ACTIVE' and private_key_sealed = :old")
                .param("new", sealed.envelope()).param("kid", sealed.keyId()).param("k", row.get().kid())
                .param("old", row.get().envelope()).update() == 1;
    }

    private record SealedKey(String kid, byte[] envelope) {
    }

    private record StoredSecret(String plaintext, byte[] ciphertext) {
    }
}
