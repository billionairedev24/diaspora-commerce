package com.afrotransact.content.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.port.SiteConfigRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSiteConfigRepository implements SiteConfigRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSiteConfigRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<SiteConfig> latest() {
        return jdbc.sql("""
                select revision, document::text as document, created_at, created_by
                from site_config_revision order by revision desc limit 1
                """)
                .query((rs, n) -> json.read(rs.getString("document"), SiteDocument.class)
                        .toConfig(rs.getInt("revision"), Timestamps.instant(rs, "created_at"), rs.getString("created_by")))
                .optional();
    }

    @Override
    public boolean insert(SiteConfig config, String summary) {
        try {
            jdbc.sql("""
                    insert into site_config_revision (revision, document, summary, created_at, created_by)
                    values (:rev, cast(:doc as jsonb), :summary, :at, :by)
                    """)
                    .param("rev", config.revision())
                    .param("doc", json.write(SiteDocument.of(config)))
                    .param("summary", summary)
                    .param("at", Timestamps.of(config.updatedAt()))
                    .param("by", config.updatedBy())
                    .update();
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public List<RevisionSummary> revisions(int limit) {
        return jdbc.sql("select revision, created_by, created_at, summary from site_config_revision order by revision desc limit :l")
                .param("l", limit)
                .query((rs, n) -> new RevisionSummary(rs.getInt("revision"), rs.getString("created_by"),
                        Timestamps.instant(rs, "created_at"), rs.getString("summary")))
                .list();
    }
}
