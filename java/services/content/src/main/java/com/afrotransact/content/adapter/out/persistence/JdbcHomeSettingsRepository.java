package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.home.HomeCaps;
import com.afrotransact.content.domain.home.HomeSettings;
import com.afrotransact.content.domain.home.ModuleRule;
import com.afrotransact.content.port.HomeSettingsRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcHomeSettingsRepository implements HomeSettingsRepository {

    private static final TypeReference<Map<String, ModuleRule>> MODULES = new TypeReference<>() { };
    private static final TypeReference<Map<String, Map<String, String>>> LABELS = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> CHANGES = new TypeReference<>() { };

    private static final String SELECT = """
            select version, dynamic, modules::text as modules, caps::text as caps,
                   department_labels::text as department_labels, reason, changed_by, changed_at, revert_of,
                   changes::text as changes
            from home_settings_version
            """;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    JdbcHomeSettingsRepository(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<Stored> latest() {
        return jdbc.sql(SELECT + " order by version desc limit 1").query(this::map).optional();
    }

    @Override
    public Optional<Stored> find(int version) {
        return jdbc.sql(SELECT + " where version = :v").param("v", version).query(this::map).optional();
    }

    @Override
    public List<Stored> history() {
        return jdbc.sql(SELECT + " order by version desc").query(this::map).list();
    }

    @Override
    public boolean insert(Stored v) {
        HomeSettings s = v.settings();
        try {
            return jdbc.sql("""
                    insert into home_settings_version (version, dynamic, modules, caps, department_labels, reason,
                        changed_by, changed_at, revert_of, changes)
                    values (:version, :dynamic, cast(:modules as jsonb), cast(:caps as jsonb),
                        cast(:labels as jsonb), :reason, :by, :at, :revertOf, cast(:changes as jsonb))
                    """)
                    .param("version", s.version()).param("dynamic", s.dynamic())
                    .param("modules", mapper.writeValueAsString(s.modules()))
                    .param("caps", mapper.writeValueAsString(s.caps()))
                    .param("labels", mapper.writeValueAsString(s.departmentLabels()))
                    .param("reason", v.reason()).param("by", v.changedBy()).param("at", Timestamps.of(v.changedAt()))
                    .param("revertOf", v.revertOf())
                    .param("changes", mapper.writeValueAsString(v.changes()))
                    .update() == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    private Stored map(ResultSet rs, int row) throws SQLException {
        int revert = rs.getInt("revert_of");
        Integer revertOf = rs.wasNull() ? null : revert;
        HomeSettings settings = new HomeSettings(rs.getInt("version"), rs.getString("dynamic"),
                mapper.readValue(rs.getString("modules"), MODULES), mapper.readValue(rs.getString("caps"), HomeCaps.class),
                mapper.readValue(rs.getString("department_labels"), LABELS), Timestamps.instant(rs, "changed_at"),
                rs.getString("changed_by"));
        return new Stored(settings, rs.getString("reason"), revertOf, mapper.readValue(rs.getString("changes"), CHANGES),
                Timestamps.instant(rs, "changed_at"), rs.getString("changed_by"));
    }
}
