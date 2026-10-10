package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.ChallengeKind;
import com.afrotransact.identity.domain.LoginTicket;
import com.afrotransact.identity.domain.SignInChallenge;
import com.afrotransact.identity.domain.SignInMethod;
import com.afrotransact.identity.port.FirstPartySignInStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFirstPartySignInStore implements FirstPartySignInStore {

    private final JdbcClient jdbc;

    JdbcFirstPartySignInStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertChallenge(SignInChallenge c) {
        jdbc.sql("""
                insert into sign_in_challenge (id, kind, user_id, client_id, passkey_challenge, rp_id, attempts,
                    created_at, expires_at, completed_at)
                values (:id, :kind, :user, :client, :challenge, :rp, :attempts, :created, :expires, :completed)
                """)
                .param("id", c.id()).param("kind", c.kind().name()).param("user", c.userId())
                .param("client", c.clientId()).param("challenge", c.passkeyChallenge()).param("rp", c.rpId())
                .param("attempts", c.attempts()).param("created", Jdbc.ts(c.createdAt()))
                .param("expires", Jdbc.ts(c.expiresAt())).param("completed", Jdbc.ts(c.completedAt()))
                .update();
    }

    @Override
    public Optional<SignInChallenge> lockChallenge(UUID id) {
        return jdbc.sql("select * from sign_in_challenge where id = :id for update").param("id", id)
                .query(JdbcFirstPartySignInStore::challenge).optional();
    }

    @Override
    public void updateChallenge(SignInChallenge c) {
        jdbc.sql("update sign_in_challenge set attempts = :attempts, completed_at = :completed where id = :id")
                .param("attempts", c.attempts()).param("completed", Jdbc.ts(c.completedAt())).param("id", c.id())
                .update();
    }

    @Override
    public void insertTicket(LoginTicket t) {
        jdbc.sql("""
                insert into login_ticket (token_hash, user_id, client_id, method, created_at, expires_at)
                values (:hash, :user, :client, :method, :created, :expires)
                """)
                .param("hash", t.tokenHash()).param("user", t.userId()).param("client", t.clientId())
                .param("method", t.method().name()).param("created", Jdbc.ts(t.createdAt()))
                .param("expires", Jdbc.ts(t.expiresAt()))
                .update();
    }

    @Override
    public Optional<LoginTicket> burnTicket(String tokenHash, Instant now) {
        return jdbc.sql("""
                update login_ticket set used_at = :now where token_hash = :hash and used_at is null
                returning token_hash, user_id, client_id, method, created_at, expires_at, used_at
                """)
                .param("now", Jdbc.ts(now)).param("hash", tokenHash)
                .query((rs, n) -> new LoginTicket(rs.getString("token_hash"), rs.getObject("user_id", UUID.class),
                        rs.getString("client_id"), SignInMethod.valueOf(rs.getString("method")),
                        Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"), null))
                .optional();
    }

    @Override
    public int purgeExpired(Instant before) {
        int challenges = jdbc.sql("delete from sign_in_challenge where expires_at < :t")
                .param("t", Jdbc.ts(before)).update();
        int tickets = jdbc.sql("delete from login_ticket where expires_at < :t").param("t", Jdbc.ts(before)).update();
        return challenges + tickets;
    }

    private static SignInChallenge challenge(ResultSet rs, int rowNum) throws SQLException {
        return new SignInChallenge(rs.getObject("id", UUID.class), ChallengeKind.valueOf(rs.getString("kind")),
                rs.getObject("user_id", UUID.class), rs.getString("client_id"), rs.getString("passkey_challenge"),
                rs.getString("rp_id"), rs.getInt("attempts"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "completed_at"));
    }
}
