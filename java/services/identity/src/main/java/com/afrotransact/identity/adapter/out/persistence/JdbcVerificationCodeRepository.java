package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.VerificationCodeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcVerificationCodeRepository implements VerificationCodeRepository {

    private final JdbcClient jdbc;

    JdbcVerificationCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(VerificationCode c) {
        jdbc.sql("""
                insert into verification_code (id, user_id, purpose, destination, code_hash, created_at, expires_at,
                    consumed_at, attempts)
                values (:id, :user, :purpose, :dest, :hash, :created, :expires, :consumed, :attempts)
                """)
                .param("id", c.id())
                .param("user", c.userId())
                .param("purpose", c.purpose().name())
                .param("dest", c.destination())
                .param("hash", c.codeHash())
                .param("created", Jdbc.ts(c.createdAt()))
                .param("expires", Jdbc.ts(c.expiresAt()))
                .param("consumed", Jdbc.ts(c.consumedAt()))
                .param("attempts", c.attempts())
                .update();
    }

    @Override
    public void update(VerificationCode c) {
        jdbc.sql("update verification_code set consumed_at = :consumed, attempts = :attempts where id = :id")
                .param("id", c.id())
                .param("consumed", Jdbc.ts(c.consumedAt()))
                .param("attempts", c.attempts())
                .update();
    }

    @Override
    public Optional<VerificationCode> latest(UUID userId, VerificationPurpose purpose) {
        return jdbc.sql("""
                select * from verification_code where user_id = :u and purpose = :p
                order by created_at desc limit 1
                """)
                .param("u", userId).param("p", purpose.name())
                .query(JdbcVerificationCodeRepository::map).optional();
    }

    @Override
    public List<Instant> issuedSince(UUID userId, VerificationPurpose purpose, Instant since) {
        return jdbc.sql("""
                select created_at from verification_code where user_id = :u and purpose = :p and created_at > :since
                order by created_at desc
                """)
                .param("u", userId).param("p", purpose.name()).param("since", Jdbc.ts(since))
                .query((rs, n) -> Jdbc.instant(rs, "created_at")).list();
    }

    private static VerificationCode map(ResultSet rs, int n) throws SQLException {
        return new VerificationCode(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                VerificationPurpose.valueOf(rs.getString("purpose")), rs.getString("destination"),
                rs.getString("code_hash"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"),
                Jdbc.instant(rs, "consumed_at"), rs.getInt("attempts"));
    }
}
