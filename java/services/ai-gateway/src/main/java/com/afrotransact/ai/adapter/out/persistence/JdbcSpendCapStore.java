package com.afrotransact.ai.adapter.out.persistence;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.ai.domain.SpendCapPreconditionException;
import com.afrotransact.ai.port.SpendCapStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

/** One row in {@code ai_spend_cap} (locked while saving, versioned) and an append-only {@code ai_spend_cap_change}. */
@Repository
class JdbcSpendCapStore implements SpendCapStore {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    JdbcSpendCapStore(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Optional<Saved> current() {
        return jdbc.sql("select cap_micros, version, changed_by, changed_at, reason from ai_spend_cap where id = 1")
                .query((rs, n) -> new Saved(rs.getLong("cap_micros"), rs.getLong("version"), rs.getString("changed_by"),
                        rs.getTimestamp("changed_at").toInstant(), rs.getString("reason")))
                .optional();
    }

    @Override
    public Saved save(long newCapMicros, long expectedVersion, String changedBy, String reason, Instant at) {
        return tx.execute(status -> {
            Optional<Long[]> before = jdbc.sql("select cap_micros, version from ai_spend_cap where id = 1 for update")
                    .query((rs, n) -> new Long[] {rs.getLong("cap_micros"), rs.getLong("version")}).optional();
            long storedVersion = before.map(b -> b[1]).orElse(0L);
            if (storedVersion != expectedVersion) {
                throw SpendCapPreconditionException.mismatch();
            }
            long version = storedVersion + 1;
            if (before.isPresent()) {
                jdbc.sql("""
                        update ai_spend_cap set cap_micros = :cap, version = :version, changed_by = :by,
                                                changed_at = :at, reason = :reason
                        where id = 1 and version = :expected
                        """)
                        .param("cap", newCapMicros).param("version", version).param("by", changedBy)
                        .param("at", Timestamp.from(at)).param("reason", reason).param("expected", storedVersion)
                        .update();
            } else {
                try {
                    jdbc.sql("""
                            insert into ai_spend_cap (id, cap_micros, version, changed_by, changed_at, reason)
                            values (1, :cap, :version, :by, :at, :reason)
                            """)
                            .param("cap", newCapMicros).param("version", version).param("by", changedBy)
                            .param("at", Timestamp.from(at)).param("reason", reason).update();
                } catch (DuplicateKeyException e) {
                    throw SpendCapPreconditionException.mismatch();
                }
            }
            jdbc.sql("""
                    insert into ai_spend_cap_change (id, version, previous_micros, previous_source, new_micros,
                                                     changed_by, changed_at, reason)
                    values (:id, :version, :previous, :source, :cap, :by, :at, :reason)
                    """)
                    .param("id", UUID.randomUUID()).param("version", version)
                    .param("previous", before.map(b -> b[0]).orElse(null), Types.BIGINT)
                    .param("source", before.isPresent() ? "SAVED" : "DEFAULT").param("cap", newCapMicros)
                    .param("by", changedBy).param("at", Timestamp.from(at)).param("reason", reason).update();
            return new Saved(newCapMicros, version, changedBy, at, reason);
        });
    }

    @Override
    public List<Change> history(int limit) {
        return jdbc.sql("""
                select id, version, previous_micros, previous_source, new_micros, changed_by, changed_at, reason
                from ai_spend_cap_change order by version desc limit :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> {
                    long previous = rs.getLong("previous_micros");
                    boolean none = rs.wasNull();
                    return new Change(rs.getObject("id", UUID.class), rs.getLong("version"), none ? null : previous,
                            "DEFAULT".equals(rs.getString("previous_source")), rs.getLong("new_micros"),
                            rs.getString("changed_by"), rs.getTimestamp("changed_at").toInstant(), rs.getString("reason"));
                }).list();
    }
}
