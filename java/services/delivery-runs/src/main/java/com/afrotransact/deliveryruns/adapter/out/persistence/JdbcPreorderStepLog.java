package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.port.PreorderSequences;
import com.afrotransact.deliveryruns.port.PreorderStepLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Scheduler step log and counters (both single-statement upserts). */
@Repository
class JdbcPreorderStepLog implements PreorderStepLog, PreorderSequences {

    private final JdbcClient jdbc;

    JdbcPreorderStepLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> done(UUID windowId) {
        return new HashSet<>(jdbc.sql("select step from preorder_comms_log where window_id = :id")
                .param("id", windowId).query(String.class).list());
    }

    @Override
    public boolean markDone(UUID windowId, String step, Instant dueAt, Instant now) {
        return jdbc.sql("""
                insert into preorder_comms_log (window_id, step, due_at, done_at) values (:id, :step, :due, :now)
                on conflict do nothing
                """).param("id", windowId).param("step", step).param("due", Sql.ts(dueAt)).param("now", Sql.ts(now))
                .update() == 1;
    }

    @Override
    public int next(String name, int first) {
        return jdbc.sql("""
                insert into preorder_sequence (name, last_value) values (:name, :first)
                on conflict (name) do update set last_value = preorder_sequence.last_value + 1
                returning last_value
                """).param("name", name).param("first", first).query(Integer.class).single();
    }
}
