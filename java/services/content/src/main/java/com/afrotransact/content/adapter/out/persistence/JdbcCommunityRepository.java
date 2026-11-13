package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.home.CommunityEntry;
import com.afrotransact.content.port.CommunityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcCommunityRepository implements CommunityRepository {

    private static final TypeReference<Map<String, String>> LABELS = new TypeReference<>() { };
    private static final TypeReference<List<String>> ALIASES = new TypeReference<>() { };

    private static final String SELECT = """
            select key, labels::text as labels, aliases::text as aliases, image_url, updated_at from community_entry
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    JdbcCommunityRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<CommunityEntry> findAll() {
        return jdbc.sql(SELECT + " order by key").query(this::map).list();
    }

    @Override
    public Optional<CommunityEntry> find(String key) {
        return jdbc.sql(SELECT + " where key = :key").param("key", key).query(this::map).optional();
    }

    @Override
    public void upsert(CommunityEntry e, String updatedBy) {
        jdbc.sql("""
                insert into community_entry (key, labels, aliases, image_url, updated_at, updated_by)
                values (:key, cast(:labels as jsonb), cast(:aliases as jsonb), :image, :at, :by)
                on conflict (key) do update set labels = excluded.labels, aliases = excluded.aliases,
                    image_url = excluded.image_url, updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """)
                .param("key", e.key()).param("labels", mapper.writeValueAsString(e.labels()))
                .param("aliases", mapper.writeValueAsString(e.aliases())).param("image", e.imageUrl())
                .param("at", Timestamps.of(e.updatedAt())).param("by", updatedBy)
                .update();
    }

    @Override
    public boolean delete(String key) {
        return jdbc.sql("delete from community_entry where key = :key").param("key", key).update() == 1;
    }

    private CommunityEntry map(ResultSet rs, int row) throws SQLException {
        return new CommunityEntry(rs.getString("key"), mapper.readValue(rs.getString("labels"), LABELS),
                mapper.readValue(rs.getString("aliases"), ALIASES), rs.getString("image_url"),
                Timestamps.instant(rs, "updated_at"));
    }
}
