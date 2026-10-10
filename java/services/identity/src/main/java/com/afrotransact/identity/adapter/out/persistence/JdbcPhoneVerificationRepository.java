package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.port.PhoneVerificationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPhoneVerificationRepository implements PhoneVerificationRepository {

    private final JdbcClient jdbc;

    JdbcPhoneVerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> verifiedPhone(UUID userId) {
        return jdbc.sql("select phone_e164 from phone_verification where user_id = :u").param("u", userId)
                .query(String.class).optional();
    }

    @Override
    public void markVerified(UUID userId, String phoneE164, Instant at) {
        jdbc.sql("""
                insert into phone_verification (user_id, phone_e164, verified_at) values (:u, :p, :at)
                on conflict (user_id) do update set phone_e164 = excluded.phone_e164, verified_at = excluded.verified_at
                """)
                .param("u", userId).param("p", phoneE164).param("at", Jdbc.ts(at))
                .update();
    }

    @Override
    public void clear(UUID userId) {
        jdbc.sql("delete from phone_verification where user_id = :u").param("u", userId).update();
    }
}
