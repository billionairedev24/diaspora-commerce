package com.afrotransact.content.adapter.out.persistence;

import java.util.List;

import com.afrotransact.content.port.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcAuditLog(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(Entry e) {
        jdbc.sql("""
                insert into audit_log (at, actor, action, entity_type, entity_id, detail)
                values (:at, :actor, :action, :type, :id, cast(:detail as jsonb))
                """)
                .param("at", Timestamps.of(e.at())).param("actor", e.actor()).param("action", e.action())
                .param("type", e.entityType()).param("id", e.entityId()).param("detail", json.write(e.detail()))
                .update();
    }

    @Override
    public List<Entry> find(String entityType, String entityId, int limit) {
        return jdbc.sql("""
                select at, actor, action, entity_type, entity_id, detail::text as detail from audit_log
                where (cast(:type as varchar) is null or entity_type = :type)
                  and (cast(:id as varchar) is null or entity_id = :id)
                order by at desc, id desc limit :limit
                """)
                .param("type", entityType).param("id", entityId).param("limit", limit)
                .query((rs, n) -> new Entry(Timestamps.instant(rs, "at"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("entity_type"), rs.getString("entity_id"), json.objectMap(rs.getString("detail"))))
                .list();
    }
}
