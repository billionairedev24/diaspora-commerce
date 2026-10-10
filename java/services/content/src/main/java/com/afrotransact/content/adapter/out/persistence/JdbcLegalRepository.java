package com.afrotransact.content.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.LegalVersionStatus;
import com.afrotransact.content.domain.legal.SemanticVersion;
import com.afrotransact.content.port.LegalRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLegalRepository implements LegalRepository {

    private static final String SELECT = """
            select id, doc_type, major, minor, patch, effective_date, status, draft_banner, bodies::text as bodies,
                   change_summary, created_by, created_at, archived_at
            from legal_version
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcLegalRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<LegalVersion> current(LegalDocumentType type) {
        return jdbc.sql(SELECT + " where doc_type = :t and status = 'CURRENT'").param("t", type.key())
                .query(this::map).optional();
    }

    @Override
    public List<LegalVersion> allCurrent() {
        return jdbc.sql(SELECT + " where status = 'CURRENT'").query(this::map).list();
    }

    @Override
    public List<LegalVersion> versions(LegalDocumentType type) {
        return jdbc.sql(SELECT + " where doc_type = :t order by major desc, minor desc, patch desc")
                .param("t", type.key()).query(this::map).list();
    }

    @Override
    public Optional<LegalVersion> version(LegalDocumentType type, SemanticVersion v) {
        return jdbc.sql(SELECT + " where doc_type = :t and major = :ma and minor = :mi and patch = :pa")
                .param("t", type.key()).param("ma", v.major()).param("mi", v.minor()).param("pa", v.patch())
                .query(this::map).optional();
    }

    @Override
    public void insert(LegalVersion v) {
        jdbc.sql("""
                insert into legal_version (id, doc_type, version, major, minor, patch, effective_date, status,
                    draft_banner, bodies, change_summary, created_at, created_by, updated_at, archived_at)
                values (:id, :t, :v, :ma, :mi, :pa, :eff, :status, :banner, cast(:bodies as jsonb), :summary, :at, :by,
                    :at, :archived)
                """)
                .param("id", v.id()).param("t", v.type().key()).param("v", v.version().toString())
                .param("ma", v.version().major()).param("mi", v.version().minor()).param("pa", v.version().patch())
                .param("eff", Date.valueOf(v.effectiveDate())).param("status", v.status().name())
                .param("banner", v.draftBanner()).param("bodies", json.write(v.bodies()))
                .param("summary", v.changeSummary()).param("at", Timestamps.of(v.createdAt()))
                .param("by", v.createdBy()).param("archived", Timestamps.of(v.archivedAt()))
                .update();
    }

    @Override
    public void update(LegalVersion v) {
        jdbc.sql("""
                update legal_version set status = :status, draft_banner = :banner, effective_date = :eff,
                    bodies = cast(:bodies as jsonb), archived_at = :archived, updated_at = now()
                where id = :id
                """)
                .param("status", v.status().name()).param("banner", v.draftBanner())
                .param("eff", Date.valueOf(v.effectiveDate())).param("bodies", json.write(v.bodies()))
                .param("archived", Timestamps.of(v.archivedAt())).param("id", v.id())
                .update();
    }

    @Override
    public long stamp() {
        return jdbc.sql("select coalesce(extract(epoch from max(updated_at)) * 1000, 0)::bigint from legal_version")
                .query(Long.class).single();
    }

    private LegalVersion map(ResultSet rs, int row) throws SQLException {
        return new LegalVersion(rs.getObject("id", UUID.class), LegalDocumentType.fromKey(rs.getString("doc_type")),
                new SemanticVersion(rs.getInt("major"), rs.getInt("minor"), rs.getInt("patch")),
                rs.getDate("effective_date").toLocalDate(), LegalVersionStatus.valueOf(rs.getString("status")),
                rs.getBoolean("draft_banner"), json.stringMap(rs.getString("bodies")), rs.getString("change_summary"),
                rs.getString("created_by"), Timestamps.instant(rs, "created_at"), Timestamps.instant(rs, "archived_at"));
    }
}
