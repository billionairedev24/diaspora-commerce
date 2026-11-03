package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.content.domain.help.HelpDraft;
import com.afrotransact.content.domain.help.HelpSection;
import com.afrotransact.content.domain.help.HelpTopic;
import com.afrotransact.content.port.HelpDraftRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHelpDraftRepository implements HelpDraftRepository {

    private static final String SELECT = """
            select slug, topic, title, summary, sections::text as sections, sort_order, change_summary, base_version,
                   row_version, created_by, created_at, updated_by, updated_at
            from help_article_draft
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcHelpDraftRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<HelpDraft> find(String slug) {
        return jdbc.sql(SELECT + " where slug = :slug").param("slug", slug).query(this::map).optional();
    }

    @Override
    public Set<String> slugsWithDraft() {
        return new HashSet<>(jdbc.sql("select slug from help_article_draft").query(String.class).list());
    }

    @Override
    public boolean insert(HelpDraft d) {
        try {
            jdbc.sql("""
                    insert into help_article_draft (slug, topic, title, summary, sections, sort_order, change_summary,
                        base_version, row_version, created_by, created_at, updated_by, updated_at)
                    values (:slug, :topic, :title, :summary, cast(:sections as jsonb), :sort, :change, :base, :row,
                        :cby, :cat, :uby, :uat)
                    """)
                    .param("slug", d.slug()).param("topic", d.topic().name()).param("title", d.title())
                    .param("summary", d.summary()).param("sections", json.write(d.sections()))
                    .param("sort", d.sortOrder()).param("change", d.changeSummary()).param("base", d.baseVersion())
                    .param("row", d.rowVersion()).param("cby", d.createdBy()).param("cat", Timestamps.of(d.createdAt()))
                    .param("uby", d.updatedBy()).param("uat", Timestamps.of(d.updatedAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public boolean update(HelpDraft d, long expectedRowVersion) {
        return jdbc.sql("""
                update help_article_draft set topic = :topic, title = :title, summary = :summary,
                    sections = cast(:sections as jsonb), sort_order = :sort, change_summary = :change,
                    row_version = :row, updated_by = :uby, updated_at = :uat
                where slug = :slug and row_version = :expected
                """)
                .param("slug", d.slug()).param("topic", d.topic().name()).param("title", d.title())
                .param("summary", d.summary()).param("sections", json.write(d.sections()))
                .param("sort", d.sortOrder()).param("change", d.changeSummary()).param("row", d.rowVersion())
                .param("uby", d.updatedBy()).param("uat", Timestamps.of(d.updatedAt()))
                .param("expected", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public void delete(String slug) {
        jdbc.sql("delete from help_article_draft where slug = :slug").param("slug", slug).update();
    }

    private HelpDraft map(ResultSet rs, int row) throws SQLException {
        HelpSection[] sections = json.read(rs.getString("sections"), HelpSection[].class);
        return new HelpDraft(rs.getString("slug"), HelpTopic.valueOf(rs.getString("topic")), rs.getString("title"),
                rs.getString("summary"), Arrays.asList(sections), rs.getInt("sort_order"),
                rs.getString("change_summary"), rs.getLong("base_version"), rs.getLong("row_version"),
                rs.getString("created_by"), Timestamps.instant(rs, "created_at"), rs.getString("updated_by"),
                Timestamps.instant(rs, "updated_at"));
    }
}
