package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.port.StripeWebhookVerifier.VerifiedEvent;
import com.afrotransact.payments.port.WebhookInbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWebhookInbox implements WebhookInbox {

    private static final String COLUMNS = "id, type, account, stripe_created_at, payload::text as payload, status, "
            + "attempts";

    private final JdbcClient jdbc;

    JdbcWebhookInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean store(VerifiedEvent e, boolean handled, Instant now) {
        return jdbc.sql("""
                insert into stripe_event (id, type, account, livemode, stripe_created_at, payload, received_at,
                    status, attempts, next_attempt_at, processed_at)
                values (:id, :type, :account, :livemode, :created, cast(:payload as jsonb), :now, :status, 0, :now,
                    :processedAt)
                on conflict (id) do nothing
                """)
                .param("id", e.id()).param("type", e.type()).param("account", e.account())
                .param("livemode", e.livemode()).param("created", Sql.ts(e.created())).param("payload", e.payload())
                .param("now", Sql.ts(now)).param("status", handled ? "RECEIVED" : "IGNORED")
                .param("processedAt", handled ? null : Sql.ts(now))
                .update() == 1;
    }

    @Override
    public Optional<InboxEvent> lockNext(Instant now) {
        return jdbc.sql("select " + COLUMNS + """
                 from stripe_event
                where status = 'RECEIVED' and next_attempt_at <= :now
                order by stripe_created_at, received_at, id
                limit 1
                for update skip locked
                """)
                .param("now", Sql.ts(now)).query(JdbcWebhookInbox::row).optional();
    }

    @Override
    public void markProcessed(String id, Instant now) {
        jdbc.sql("update stripe_event set status = 'PROCESSED', processed_at = :now, attempts = attempts + 1, "
                + "last_error = null where id = :id").param("now", Sql.ts(now)).param("id", id).update();
    }

    @Override
    public void markFailed(String id, String error, Instant nextAttemptAt, boolean giveUp) {
        jdbc.sql("""
                update stripe_event set attempts = attempts + 1, last_error = :error, next_attempt_at = :next,
                    status = :status
                where id = :id
                """)
                .param("error", error).param("next", Sql.ts(nextAttemptAt))
                .param("status", giveUp ? "FAILED" : "RECEIVED").param("id", id)
                .update();
    }

    @Override
    public Optional<InboxEvent> find(String id) {
        return jdbc.sql("select " + COLUMNS + " from stripe_event where id = :id").param("id", id)
                .query(JdbcWebhookInbox::row).optional();
    }

    private static InboxEvent row(ResultSet rs, int n) throws SQLException {
        return new InboxEvent(rs.getString("id"), rs.getString("type"), rs.getString("account"),
                Sql.instant(rs, "stripe_created_at"), rs.getString("payload"), rs.getString("status"),
                rs.getInt("attempts"));
    }
}
