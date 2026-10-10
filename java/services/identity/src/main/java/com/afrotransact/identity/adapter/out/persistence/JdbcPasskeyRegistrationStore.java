package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.PasskeyRegistration;
import com.afrotransact.identity.port.PasskeyRegistrationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPasskeyRegistrationStore implements PasskeyRegistrationStore {

    private final JdbcClient jdbc;

    JdbcPasskeyRegistrationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PasskeyRegistration r) {
        jdbc.sql("""
                insert into passkey_registration (id, user_id, challenge, user_handle, rp_id, label, created_at,
                    expires_at, completed_at)
                values (:id, :user, :challenge, :handle, :rp, :label, :created, :expires, null)
                """)
                .param("id", r.id()).param("user", r.userId()).param("challenge", r.challenge())
                .param("handle", r.userHandle()).param("rp", r.rpId()).param("label", r.label())
                .param("created", Timestamp.from(r.createdAt())).param("expires", Timestamp.from(r.expiresAt()))
                .update();
    }

    @Override
    public Optional<PasskeyRegistration> lock(UUID id) {
        return jdbc.sql("select * from passkey_registration where id = :id for update").param("id", id)
                .query(JdbcPasskeyRegistrationStore::row).optional();
    }

    @Override
    public void complete(UUID id, Instant at) {
        jdbc.sql("update passkey_registration set completed_at = :at where id = :id")
                .param("at", Timestamp.from(at)).param("id", id).update();
    }

    @Override
    public int purgeExpired(Instant before) {
        return jdbc.sql("delete from passkey_registration where expires_at < :before")
                .param("before", Timestamp.from(before)).update();
    }

    private static PasskeyRegistration row(ResultSet rs, int n) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new PasskeyRegistration(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("challenge"), rs.getString("user_handle"), rs.getString("rp_id"), rs.getString("label"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }
}
