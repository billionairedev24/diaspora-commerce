package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.port.StepUpStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStepUpStore implements StepUpStore {

    private final JdbcClient jdbc;

    JdbcStepUpStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt) {
        jdbc.sql("insert into step_up_proof (token_hash, user_id, created_at, expires_at) "
                + "values (:h, :u, :c, :e)")
                .param("h", tokenHash).param("u", userId).param("c", Jdbc.ts(createdAt)).param("e", Jdbc.ts(expiresAt))
                .update();
    }

    @Override
    public boolean isValid(String tokenHash, UUID userId, Instant now) {
        return jdbc.sql("select count(*) from step_up_proof where token_hash = :h and user_id = :u "
                + "and expires_at > :now")
                .param("h", tokenHash).param("u", userId).param("now", Jdbc.ts(now)).query(Long.class).single() > 0;
    }

    @Override
    public int deleteExpired(Instant now) {
        return jdbc.sql("delete from step_up_proof where expires_at <= :now").param("now", Jdbc.ts(now)).update();
    }
}
