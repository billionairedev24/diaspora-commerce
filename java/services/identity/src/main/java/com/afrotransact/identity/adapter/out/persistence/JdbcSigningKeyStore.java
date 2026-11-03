package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.domain.SigningKeyStatus;
import com.afrotransact.identity.port.SigningKeyStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSigningKeyStore implements SigningKeyStore {

    private final JdbcClient jdbc;

    JdbcSigningKeyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SigningKey> all() {
        return jdbc.sql("select * from signing_key order by created_at desc, kid desc")
                .query((rs, n) -> new SigningKey(rs.getString("kid"), rs.getString("algorithm"),
                        SigningKeyStatus.valueOf(rs.getString("status")), rs.getString("public_key_pem"),
                        Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "rotated_at"),
                        Jdbc.instant(rs, "retire_after"), Jdbc.instant(rs, "retired_at")))
                .list();
    }

    @Override
    public Optional<byte[]> sealedPrivateKey(String kid) {
        return jdbc.sql("select private_key_sealed from signing_key where kid = :kid")
                .param("kid", kid).query((rs, n) -> rs.getBytes("private_key_sealed")).optional();
    }

    @Override
    public boolean insertActive(SigningKey key, byte[] sealedPrivateKey, String sealKeyId, String by) {
        return jdbc.sql("""
                insert into signing_key (kid, algorithm, status, public_key_pem, private_key_sealed, seal_key_id,
                    created_at, created_by)
                values (:kid, :alg, 'ACTIVE', :pub, :priv, :seal, :at, :by)
                on conflict do nothing
                """)
                .param("kid", key.kid())
                .param("alg", key.algorithm())
                .param("pub", key.publicKeyPem())
                .param("priv", sealedPrivateKey)
                .param("seal", sealKeyId)
                .param("at", Jdbc.ts(key.createdAt()))
                .param("by", by)
                .update() == 1;
    }

    @Override
    public void markRetiring(String kid, Instant rotatedAt, Instant retireAfter) {
        jdbc.sql("""
                update signing_key set status = 'RETIRING', rotated_at = :at, retire_after = :after,
                    private_key_sealed = null, seal_key_id = null
                where kid = :kid and status = 'ACTIVE'
                """)
                .param("kid", kid).param("at", Jdbc.ts(rotatedAt)).param("after", Jdbc.ts(retireAfter)).update();
    }

    @Override
    public void markRetired(String kid, Instant at) {
        jdbc.sql("update signing_key set status = 'RETIRED', retired_at = :at where kid = :kid and status = 'RETIRING'")
                .param("kid", kid).param("at", Jdbc.ts(at)).update();
    }
}
