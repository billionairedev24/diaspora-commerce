package com.afrotransact.payments.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code INSERT … ON CONFLICT DO NOTHING} on (scope, key): the first request claims the key; a concurrent duplicate
 * waits on the primary key until the first commits (and, at SERIALIZABLE, is retried), then reads the stored
 * response. A null key disables replay (natural business keys still protect the write).
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcIdempotencyStore(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public <T> Optional<T> claim(String scope, String key, String fingerprint, Class<T> responseType) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
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
        Stored stored = jdbc.sql("""
                select fingerprint, response::text as response from idempotency_record
                where scope = :scope and idem_key = :key
                """)
                .param("scope", scope).param("key", key)
                .query((rs, n) -> new Stored(rs.getString("fingerprint"), rs.getString("response")))
                .single();
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ConflictException("payments.idempotency-key-reused",
                    "Idempotency-Key " + key + " was already used for a different request");
        }
        if (stored.response() == null) {
            throw new ConflictException("payments.request-in-flight", "A request with this Idempotency-Key is "
                    + "still being processed; retry shortly");
        }
        return Optional.of(json.read(stored.response(), responseType));
    }

    @Override
    public void complete(String scope, String key, Object response) {
        if (key == null || key.isBlank()) {
            return;
        }
        jdbc.sql("update idempotency_record set response = cast(:response as jsonb) where scope = :scope "
                        + "and idem_key = :key")
                .param("response", json.write(response)).param("scope", scope).param("key", key)
                .update();
    }

    private record Stored(String fingerprint, String response) {
    }
}
