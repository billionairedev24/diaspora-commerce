package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.port.IdempotencyStore;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code INSERT … ON CONFLICT DO NOTHING} on (scope, key): the first request claims the key; a concurrent duplicate
 * waits on the primary key until the first commits, then reads the stored booking id (or gets a conflict while the
 * first is still running / when the key was used for a different request).
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> claim(String scope, String key, String fingerprint) {
        int inserted = jdbc.sql("""
                insert into idempotency_record (scope, idem_key, fingerprint, created_at)
                values (:scope, :key, :fp, :now)
                on conflict (scope, idem_key) do nothing
                """)
                .param("scope", scope).param("key", key).param("fp", fingerprint)
                .param("now", Timestamp.from(Instant.now()))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        Stored stored = jdbc.sql("select fingerprint, booking_id from idempotency_record where scope = :scope "
                        + "and idem_key = :key")
                .param("scope", scope).param("key", key)
                .query((rs, n) -> new Stored(rs.getString("fingerprint"), rs.getObject("booking_id", UUID.class)))
                .single();
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ConflictException("bookings.idempotency-key-reused",
                    "Idempotency-Key " + key + " was already used for a different request");
        }
        if (stored.bookingId() == null) {
            throw new ConflictException("bookings.request-in-flight",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
        return Optional.of(stored.bookingId());
    }

    @Override
    public void complete(String scope, String key, UUID bookingId) {
        jdbc.sql("update idempotency_record set booking_id = :b where scope = :scope and idem_key = :key")
                .param("b", bookingId).param("scope", scope).param("key", key).update();
    }

    private record Stored(String fingerprint, UUID bookingId) {
    }
}
