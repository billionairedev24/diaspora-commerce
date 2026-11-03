package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.taxonomy.AuditSubject;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxonomyAuditRepository implements TaxonomyAuditRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcTaxonomyAuditRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(TaxonomyAuditEntry e) {
        jdbc.sql("""
                insert into taxonomy_audit (id, occurred_at, actor_id, action, subject_kind, subject_id,
                                            subject_label, reason, details)
                values (:id, :at, :actor, :action, :kind, :subject, :label, :reason, cast(:details as jsonb))
                """)
                .param("id", e.id())
                .param("at", Timestamp.from(e.occurredAt()))
                .param("actor", e.actorId())
                .param("action", e.action())
                .param("kind", e.subjectKind().name())
                .param("subject", e.subjectId())
                .param("label", e.subjectLabel())
                .param("reason", e.reason())
                .param("details", json.write(e.details()))
                .update();
    }

    @Override
    public List<TaxonomyAuditEntry> find(String subjectId, int limit) {
        String where = subjectId == null ? "" : "where subject_id = :subject";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                select id, occurred_at, actor_id, action, subject_kind, subject_id, subject_label, reason,
                       details::text as details
                from taxonomy_audit
                """ + where + " order by occurred_at desc, id limit :limit");
        if (subjectId != null) {
            spec = spec.param("subject", subjectId);
        }
        return spec.param("limit", limit).query(this::map).list();
    }

    private TaxonomyAuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> details = new LinkedHashMap<>(json.objectMap(rs.getString("details")));
        return new TaxonomyAuditEntry(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_id"), rs.getString("action"), AuditSubject.valueOf(rs.getString("subject_kind")),
                rs.getString("subject_id"), rs.getString("subject_label"), rs.getString("reason"), details);
    }
}
