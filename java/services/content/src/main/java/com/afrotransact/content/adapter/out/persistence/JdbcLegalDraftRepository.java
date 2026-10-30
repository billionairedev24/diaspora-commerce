package com.afrotransact.content.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalDraft;
import com.afrotransact.content.domain.legal.SemanticVersion;
import com.afrotransact.content.port.LegalDraftRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLegalDraftRepository implements LegalDraftRepository {

    private static final String SELECT = """
            select doc_type, version, effective_date, draft_banner, bodies::text as bodies,
                   needs_review::text as needs_review, change_summary, base_version, row_version,
                   created_by, created_at, updated_by, updated_at
            from legal_draft
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcLegalDraftRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<LegalDraft> find(LegalDocumentType type) {
        return jdbc.sql(SELECT + " where doc_type = :t").param("t", type.key()).query(this::map).optional();
    }

    @Override
    public List<LegalDraft> findAll() {
        return jdbc.sql(SELECT).query(this::map).list();
    }

    @Override
    public boolean insert(LegalDraft d) {
        try {
            jdbc.sql("""
                    insert into legal_draft (doc_type, version, effective_date, draft_banner, bodies, needs_review,
                        change_summary, base_version, row_version, created_by, created_at, updated_by, updated_at)
                    values (:t, :v, :eff, :banner, cast(:bodies as jsonb), cast(:review as jsonb), :summary, :base,
                        :row, :cby, :cat, :uby, :uat)
                    """)
                    .param("t", d.type().key()).param("v", d.version().toString())
                    .param("eff", Date.valueOf(d.effectiveDate())).param("banner", d.draftBanner())
                    .param("bodies", json.write(d.bodies())).param("review", json.write(d.needsReview()))
                    .param("summary", d.changeSummary()).param("base", d.baseVersion().toString())
                    .param("row", d.rowVersion()).param("cby", d.createdBy()).param("cat", Timestamps.of(d.createdAt()))
                    .param("uby", d.updatedBy()).param("uat", Timestamps.of(d.updatedAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public boolean update(LegalDraft d, long expectedRowVersion) {
        return jdbc.sql("""
                update legal_draft set version = :v, effective_date = :eff, draft_banner = :banner,
                    bodies = cast(:bodies as jsonb), needs_review = cast(:review as jsonb), change_summary = :summary,
                    row_version = :row, updated_by = :uby, updated_at = :uat
                where doc_type = :t and row_version = :expected
                """)
                .param("t", d.type().key()).param("v", d.version().toString())
                .param("eff", Date.valueOf(d.effectiveDate())).param("banner", d.draftBanner())
                .param("bodies", json.write(d.bodies())).param("review", json.write(d.needsReview()))
                .param("summary", d.changeSummary()).param("row", d.rowVersion()).param("uby", d.updatedBy())
                .param("uat", Timestamps.of(d.updatedAt())).param("expected", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public void delete(LegalDocumentType type) {
        jdbc.sql("delete from legal_draft where doc_type = :t").param("t", type.key()).update();
    }

    private LegalDraft map(ResultSet rs, int row) throws SQLException {
        return new LegalDraft(LegalDocumentType.fromKey(rs.getString("doc_type")),
                SemanticVersion.parse(rs.getString("version")), rs.getDate("effective_date").toLocalDate(),
                rs.getBoolean("draft_banner"), json.stringMap(rs.getString("bodies")),
                Set.copyOf(json.strings(rs.getString("needs_review"))), rs.getString("change_summary"),
                SemanticVersion.parse(rs.getString("base_version")), rs.getLong("row_version"),
                rs.getString("created_by"), Timestamps.instant(rs, "created_at"), rs.getString("updated_by"),
                Timestamps.instant(rs, "updated_at"));
    }
}
