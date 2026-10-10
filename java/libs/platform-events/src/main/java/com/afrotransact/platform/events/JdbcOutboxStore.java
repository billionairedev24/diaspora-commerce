package com.afrotransact.platform.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of {@link OutboxStore} (table created by db/platform/V0_0_1__outbox_inbox.sql). */
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(EventEnvelope e) {
        jdbc.sql("""
                insert into platform_outbox (id, type, aggregate_type, aggregate_id, occurred_at, payload)
                values (:id, :type, :aggregateType, :aggregateId, :occurredAt, cast(:payload as jsonb))
                """)
                .param("id", e.id())
                .param("type", e.type())
                .param("aggregateType", e.aggregateType())
                .param("aggregateId", e.aggregateId())
                .param("occurredAt", Timestamp.from(e.occurredAt()))
                .param("payload", e.payload())
                .update();
    }

    @Override
    public List<EventEnvelope> lockBatch(int limit) {
        return jdbc.sql("""
                select id, type, aggregate_type, aggregate_id, occurred_at, payload::text as payload
                from platform_outbox
                where published_at is null
                order by occurred_at
                limit :limit
                for update skip locked
                """)
                .param("limit", limit)
                .query((rs, n) -> new EventEnvelope(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("payload")))
                .list();
    }

    @Override
    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("update platform_outbox set published_at = :now where id in (:ids)")
                .param("now", Timestamp.from(Instant.now()))
                .param("ids", ids)
                .update();
    }
}
