package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.help.HelpArticleStatus;
import com.afrotransact.content.domain.help.HelpRevision;
import com.afrotransact.content.domain.help.HelpSection;
import com.afrotransact.content.domain.help.HelpTopic;
import com.afrotransact.content.port.HelpRevisionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHelpRevisionRepository implements HelpRevisionRepository {

    private static final String SELECT = """
            select slug, revision, topic, title, summary, sections::text as sections, status, sort_order,
                   change_summary, reason, published_by, published_at
            from help_article_revision
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcHelpRevisionRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<HelpRevision> list(String slug) {
        return jdbc.sql(SELECT + " where slug = :slug order by revision desc").param("slug", slug)
                .query(this::map).list();
    }

    @Override
    public Optional<HelpRevision> find(String slug, int revision) {
        return jdbc.sql(SELECT + " where slug = :slug and revision = :rev").param("slug", slug).param("rev", revision)
                .query(this::map).optional();
    }

    @Override
    public int nextRevision(String slug) {
        return jdbc.sql("select coalesce(max(revision), 0) + 1 from help_article_revision where slug = :slug")
                .param("slug", slug).query(Integer.class).single();
    }

    @Override
    public void append(HelpRevision r) {
        jdbc.sql("""
                insert into help_article_revision (slug, revision, topic, title, summary, sections, status, sort_order,
                    change_summary, reason, published_by, published_at)
                values (:slug, :rev, :topic, :title, :summary, cast(:sections as jsonb), :status, :sort, :change,
                    :reason, :by, :at)
                """)
                .param("slug", r.slug()).param("rev", r.revision()).param("topic", r.topic().name())
                .param("title", r.title()).param("summary", r.summary()).param("sections", json.write(r.sections()))
                .param("status", r.status().name()).param("sort", r.sortOrder()).param("change", r.changeSummary())
                .param("reason", r.reason()).param("by", r.publishedBy()).param("at", Timestamps.of(r.publishedAt()))
                .update();
    }

    private HelpRevision map(ResultSet rs, int row) throws SQLException {
        HelpSection[] sections = json.read(rs.getString("sections"), HelpSection[].class);
        return new HelpRevision(rs.getString("slug"), rs.getInt("revision"), HelpTopic.valueOf(rs.getString("topic")),
                rs.getString("title"), rs.getString("summary"), Arrays.asList(sections),
                HelpArticleStatus.valueOf(rs.getString("status")), rs.getInt("sort_order"),
                rs.getString("change_summary"), rs.getString("reason"), rs.getString("published_by"),
                Timestamps.instant(rs, "published_at"));
    }
}
