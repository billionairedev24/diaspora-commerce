package com.afrotransact.orders.adapter.out.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.port.FollowUpRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** follow_up_task: claimed with a lease in one statement ({@code FOR UPDATE SKIP LOCKED}) so runners never collide. */
@Repository
class JdbcFollowUpRepository implements FollowUpRepository {

    private final JdbcClient jdbc;

    JdbcFollowUpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(FollowUp t) {
        jdbc.sql("""
                insert into follow_up_task (id, task_key, kind, order_id, shipment_id, status, attempts, next_attempt_at,
                    created_at, updated_at)
                values (:id, :key, :kind, :order, :shipment, 'PENDING', 0, :next, :next, :next)
                on conflict (task_key) do nothing
                """)
                .param("id", Sql.uuid(t.id())).param("key", t.key()).param("kind", t.kind().name())
                .param("order", Sql.uuid(t.orderId())).param("shipment", Sql.uuid(t.shipmentId()))
                .param("next", Sql.ts(t.nextAttemptAt()))
                .update();
    }

    @Override
    public List<FollowUp> claimDue(Instant now, Duration lease, int limit) {
        return jdbc.sql("""
                update follow_up_task t set next_attempt_at = :leaseUntil, updated_at = :now
                where t.id in (select id from follow_up_task
                               where status = 'PENDING' and next_attempt_at <= :now
                               order by next_attempt_at limit :limit for update skip locked)
                returning t.id, t.task_key, t.kind, t.order_id, t.shipment_id, t.attempts, t.next_attempt_at
                """)
                .param("leaseUntil", Sql.ts(now.plus(lease))).param("now", Sql.ts(now)).param("limit", limit)
                .query((rs, n) -> new FollowUp(Sql.str(rs, "id"), rs.getString("task_key"),
                        Kind.valueOf(rs.getString("kind")), Sql.str(rs, "order_id"), Sql.str(rs, "shipment_id"),
                        rs.getInt("attempts"), Sql.instant(rs, "next_attempt_at")))
                .list();
    }

    @Override
    public void done(String id, Instant at) {
        jdbc.sql("update follow_up_task set status = 'DONE', last_error = null, updated_at = :at where id = :id")
                .param("at", Sql.ts(at)).param("id", UUID.fromString(id)).update();
    }

    @Override
    public void retry(String id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                update follow_up_task set attempts = :attempts, next_attempt_at = :next, last_error = :error,
                    updated_at = now()
                where id = :id
                """)
                .param("attempts", attempts).param("next", Sql.ts(nextAttemptAt)).param("error", error)
                .param("id", UUID.fromString(id)).update();
    }

    @Override
    public void fail(String id, int attempts, String error) {
        jdbc.sql("""
                update follow_up_task set status = 'FAILED', attempts = :attempts, last_error = :error,
                    updated_at = now()
                where id = :id
                """)
                .param("attempts", attempts).param("error", error).param("id", UUID.fromString(id)).update();
    }
}
