package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpArticleStatus;
import com.afrotransact.content.domain.help.HelpSection;
import com.afrotransact.content.domain.help.HelpTopic;
import com.afrotransact.content.port.HelpArticleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHelpArticleRepository implements HelpArticleRepository {

    private static final String SELECT = """
            select slug, topic, title, summary, sections::text as sections, status, sort_order, updated_at, version, updated_by
            from help_article
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcHelpArticleRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<HelpArticle> findAll() {
        List<HelpTopic> order = List.of(HelpTopic.values());
        return jdbc.sql(SELECT).query(this::map).list().stream()
                .sorted((a, b) -> {
                    int t = Integer.compare(order.indexOf(a.topic()), order.indexOf(b.topic()));
                    if (t != 0) {
                        return t;
                    }
                    int s = Integer.compare(a.sortOrder(), b.sortOrder());
                    return s != 0 ? s : a.title().compareToIgnoreCase(b.title());
                })
                .toList();
    }

    @Override
    public Optional<HelpArticle> find(String slug) {
        return jdbc.sql(SELECT + " where slug = :slug").param("slug", slug).query(this::map).optional();
    }

    @Override
    public void insert(HelpArticle a) {
        jdbc.sql("""
                insert into help_article (slug, topic, title, summary, sections, status, sort_order, updated_at, version, updated_by)
                values (:slug, :topic, :title, :summary, cast(:sections as jsonb), :status, :sort, :updated, :version, :by)
                """)
                .param("slug", a.slug()).param("topic", a.topic().name()).param("title", a.title())
                .param("summary", a.summary()).param("sections", json.write(a.sections()))
                .param("status", a.status().name()).param("sort", a.sortOrder())
                .param("updated", Timestamps.of(a.updatedAt())).param("version", a.version())
                .param("by", a.updatedBy())
                .update();
    }

    @Override
    public boolean update(HelpArticle a, long expectedVersion) {
        return jdbc.sql("""
                update help_article set topic = :topic, title = :title, summary = :summary,
                    sections = cast(:sections as jsonb), status = :status, sort_order = :sort, updated_at = :updated,
                    version = :version, updated_by = :by
                where slug = :slug and version = :expected
                """)
                .param("slug", a.slug()).param("topic", a.topic().name()).param("title", a.title())
                .param("summary", a.summary()).param("sections", json.write(a.sections()))
                .param("status", a.status().name()).param("sort", a.sortOrder())
                .param("updated", Timestamps.of(a.updatedAt())).param("version", a.version())
                .param("by", a.updatedBy()).param("expected", expectedVersion)
                .update() == 1;
    }

    private HelpArticle map(ResultSet rs, int row) throws SQLException {
        HelpSection[] sections = json.read(rs.getString("sections"), HelpSection[].class);
        return new HelpArticle(rs.getString("slug"), HelpTopic.valueOf(rs.getString("topic")), rs.getString("title"),
                rs.getString("summary"), Arrays.asList(sections), HelpArticleStatus.valueOf(rs.getString("status")),
                rs.getInt("sort_order"), Timestamps.instant(rs, "updated_at"), rs.getLong("version"),
                rs.getString("updated_by"));
    }
}
