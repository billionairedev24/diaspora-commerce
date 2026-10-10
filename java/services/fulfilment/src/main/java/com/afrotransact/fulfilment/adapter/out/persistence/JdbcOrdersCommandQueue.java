package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import com.afrotransact.fulfilment.port.OrdersCommandQueue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * orders_command table. A courier command keeps the customer's handover code only while it waits; the code is
 * removed from the stored document as soon as the command is done or failed.
 */
@Repository
class JdbcOrdersCommandQueue implements OrdersCommandQueue {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcOrdersCommandQueue(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public long enqueue(Command c, Instant now) {
        return jdbc.sql("""
                insert into orders_command (shipment_id, kind, idempotency_key, target, detail, courier, status,
                    attempts, next_attempt_at, created_at, updated_at)
                values (:shipment, :kind, :key, :target, :detail, cast(:courier as jsonb), 'PENDING', 0, :now, :now,
                    :now)
                returning id
                """)
                .param("shipment", c.shipmentId()).param("kind", c.kind()).param("key", c.idempotencyKey())
                .param("target", c.target()).param("detail", c.detail()).param("courier", json.write(c.courier()))
                .param("now", Rows.ts(now))
                .query(Long.class).single();
    }

    @Override
    public boolean hasPending(String shipmentId) {
        return jdbc.sql("select exists(select 1 from orders_command where shipment_id = :s and status = 'PENDING')")
                .param("s", shipmentId).query(Boolean.class).single();
    }

    @Override
    public List<Command> claimDue(int limit, Instant now) {
        return jdbc.sql("""
                select * from orders_command c
                where c.status = 'PENDING' and c.next_attempt_at <= :now
                  and not exists (select 1 from orders_command o where o.shipment_id = c.shipment_id
                                  and o.status = 'PENDING' and o.id < c.id)
                order by c.id limit :limit
                for update skip locked
                """).param("now", Rows.ts(now)).param("limit", limit).query(this::map).list();
    }

    @Override
    public void done(long id, Instant now) {
        jdbc.sql("""
                update orders_command set status = 'DONE', attempts = attempts + 1, updated_at = :now,
                    courier = courier - 'handoverCode'
                where id = :id
                """).param("now", Rows.ts(now)).param("id", id).update();
    }

    @Override
    public void retry(long id, String error, Instant nextAttempt, Instant now) {
        jdbc.sql("""
                update orders_command set attempts = attempts + 1, last_error = :error, next_attempt_at = :next,
                    updated_at = :now
                where id = :id
                """).param("error", truncate(error)).param("next", Rows.ts(nextAttempt)).param("now", Rows.ts(now))
                .param("id", id).update();
    }

    @Override
    public void failed(long id, String error, Instant now) {
        jdbc.sql("""
                update orders_command set status = 'FAILED', attempts = attempts + 1, last_error = :error,
                    updated_at = :now, courier = courier - 'handoverCode'
                where id = :id
                """).param("error", truncate(error)).param("now", Rows.ts(now)).param("id", id).update();
    }

    @Override
    public List<Command> failures(int limit) {
        return jdbc.sql("select * from orders_command where status = 'FAILED' order by updated_at desc limit :n")
                .param("n", limit).query(this::map).list();
    }

    private Command map(ResultSet rs, int n) throws SQLException {
        return new Command(rs.getLong("id"), rs.getString("shipment_id"), rs.getString("kind"),
                rs.getString("idempotency_key"), rs.getString("target"), rs.getString("detail"),
                json.courier(rs.getString("courier")), rs.getInt("attempts"), rs.getString("last_error"));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
