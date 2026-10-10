package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;

import com.afrotransact.seller.port.WebhookInbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** INSERT … ON CONFLICT DO NOTHING on the Stripe event id; a concurrent duplicate waits for the first to commit. */
@Repository
class JdbcWebhookInbox implements WebhookInbox {

    private final JdbcClient jdbc;

    JdbcWebhookInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean firstDelivery(String eventId, String endpoint, String type, String accountId, long created) {
        return jdbc.sql("""
                insert into stripe_webhook_event (event_id, endpoint, type, account_id, created, received_at)
                values (:id, :endpoint, :type, :account, :created, :now)
                on conflict (event_id) do nothing
                """)
                .param("id", eventId).param("endpoint", endpoint).param("type", type).param("account", accountId)
                .param("created", created).param("now", Timestamp.from(Instant.now()))
                .update() == 1;
    }

    @Override
    public void outcome(String eventId, String outcome) {
        jdbc.sql("update stripe_webhook_event set outcome = :o where event_id = :id").param("o", outcome)
                .param("id", eventId).update();
    }
}
