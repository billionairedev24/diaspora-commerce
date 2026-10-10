package com.afrotransact.platform.events;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of {@link InboxDeduplicator} using INSERT … ON CONFLICT DO NOTHING. */
public class JdbcInboxDeduplicator implements InboxDeduplicator {

    private final JdbcClient jdbc;

    public JdbcInboxDeduplicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstDelivery(String consumer, UUID eventId) {
        int inserted = jdbc.sql("""
                insert into platform_inbox (consumer, event_id, processed_at)
                values (:consumer, :eventId, now())
                on conflict do nothing
                """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update();
        return inserted == 1;
    }
}
