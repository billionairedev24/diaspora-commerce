package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.afrotransact.trustops.domain.AuditDraft;
import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.AuditFacts;
import com.afrotransact.trustops.port.AuditLogRepository;
import com.afrotransact.trustops.port.AuditQuery;
import com.afrotransact.trustops.port.Page;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** INSERT and SELECT only: the table's triggers reject UPDATE, DELETE and TRUNCATE. */
@Repository
class JdbcAuditLogRepository implements AuditLogRepository {

    /** Advisory lock key of the audit chain ("AUDIT" in ASCII). */
    private static final long CHAIN_LOCK = 0x4155444954L;

    private final JdbcClient jdbc;

    JdbcAuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockChain() {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(:key)) l").param("key", CHAIN_LOCK)
                .query(Integer.class).single();
    }

    @Override
    public Optional<AuditEntry> last() {
        return jdbc.sql("select * from audit_entry order by seq desc limit 1").query(JdbcAuditLogRepository::map)
                .optional();
    }

    @Override
    public void insert(AuditEntry e) {
        AuditDraft d = e.draft();
        AuditFacts f = d.facts();
        jdbc.sql("""
                insert into audit_entry (seq, id, source_event_id, source_topic, event_type, actor_id, actor_type,
                    action, entity_type, entity_id, org_id, summary, payload, occurred_at, recorded_at, prev_hash, hash)
                values (:seq, :id, :sourceEventId, :topic, :type, :actorId, :actorType, :action, :entityType,
                    :entityId, :orgId, :summary, :payload, :occurredAt, :recordedAt, :prevHash, :hash)
                """)
                .param("seq", e.seq())
                .param("id", d.id())
                .param("sourceEventId", d.sourceEventId())
                .param("topic", d.sourceTopic())
                .param("type", d.eventType())
                .param("actorId", f.actorId())
                .param("actorType", f.actorType())
                .param("action", f.action())
                .param("entityType", f.entityType())
                .param("entityId", f.entityId())
                .param("orgId", f.orgId())
                .param("summary", f.summary())
                .param("payload", d.payload())
                .param("occurredAt", Sql.ts(d.occurredAt()))
                .param("recordedAt", Sql.ts(d.recordedAt()))
                .param("prevHash", e.prevHash())
                .param("hash", e.hash())
                .update();
    }

    @Override
    public boolean existsBySourceEvent(UUID sourceEventId) {
        return jdbc.sql("select count(*) from audit_entry where source_event_id = :id").param("id", sourceEventId)
                .query(Long.class).single() > 0;
    }

    @Override
    public Optional<AuditEntry> findBySeq(long seq) {
        return jdbc.sql("select * from audit_entry where seq = :seq").param("seq", seq)
                .query(JdbcAuditLogRepository::map).optional();
    }

    @Override
    public List<AuditEntry> range(long fromSeq, long toSeq, int limit) {
        return jdbc.sql("select * from audit_entry where seq between :from and :to order by seq limit :limit")
                .param("from", fromSeq).param("to", toSeq).param("limit", limit)
                .query(JdbcAuditLogRepository::map).list();
    }

    @Override
    public Page<AuditEntry> search(AuditQuery q, int page, int size) {
        Filter f = filter(q);
        long total = jdbc.sql("select count(*) from audit_entry" + f.clause()).params(f.params()).query(Long.class)
                .single();
        List<AuditEntry> items = jdbc.sql("select * from audit_entry" + f.clause()
                        + " order by seq desc limit :limit offset :offset")
                .params(f.params()).param("limit", size).param("offset", Sql.offset(page, size))
                .query(JdbcAuditLogRepository::map).list();
        return new Page<>(items, total, page, size);
    }

    @Override
    public int forEach(AuditQuery q, int max, Consumer<AuditEntry> sink) {
        Filter f = filter(q);
        AtomicInteger count = new AtomicInteger();
        RowCallbackHandler handler = rs -> {
            sink.accept(map(rs, count.get()));
            count.incrementAndGet();
        };
        jdbc.sql("select * from audit_entry" + f.clause() + " order by seq limit :limit")
                .params(f.params()).param("limit", max).query(handler);
        return count.get();
    }

    private record Filter(String clause, Map<String, Object> params) {
    }

    private static Filter filter(AuditQuery q) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        add(where, params, "actor_id", "actorId", q.actorId());
        add(where, params, "entity_type", "entityType", q.entityType());
        add(where, params, "entity_id", "entityId", q.entityId());
        add(where, params, "action", "action", q.action());
        add(where, params, "org_id", "orgId", q.orgId());
        if (q.from() != null) {
            where.add("occurred_at >= :from");
            params.put("from", Sql.ts(q.from()));
        }
        if (q.to() != null) {
            where.add("occurred_at < :to");
            params.put("to", Sql.ts(q.to()));
        }
        return new Filter(where.isEmpty() ? "" : " where " + String.join(" and ", where), params);
    }

    private static void add(List<String> where, Map<String, Object> params, String column, String name,
                            String value) {
        if (value != null && !value.isBlank()) {
            where.add(column + " = :" + name);
            params.put(name, value);
        }
    }

    private static AuditEntry map(ResultSet rs, int row) throws SQLException {
        AuditFacts facts = new AuditFacts(rs.getString("actor_id"), rs.getString("actor_type"),
                rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("org_id"), rs.getString("summary"));
        AuditDraft draft = new AuditDraft(rs.getObject("id", UUID.class), rs.getObject("source_event_id", UUID.class),
                rs.getString("source_topic"), rs.getString("event_type"), facts, rs.getString("payload"),
                Sql.instant(rs, "occurred_at"), Sql.instant(rs, "recorded_at"));
        return new AuditEntry(rs.getLong("seq"), draft, rs.getString("prev_hash"), rs.getString("hash"));
    }
}
