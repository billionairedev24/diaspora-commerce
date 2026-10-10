package com.afrotransact.content.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.flag.FeatureFlag;
import com.afrotransact.content.domain.flag.FlagTargeting;
import com.afrotransact.content.port.FlagRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFlagRepository implements FlagRepository {

    private static final String SELECT = """
            select key, description, enabled, rollout_percentage, kill_switch, targeting::text as targeting, version,
                   updated_at, updated_by
            from feature_flag
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcFlagRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<FeatureFlag> findAll() {
        return jdbc.sql(SELECT + " order by key").query(this::map).list();
    }

    @Override
    public Optional<FeatureFlag> find(String key) {
        return jdbc.sql(SELECT + " where key = :key").param("key", key).query(this::map).optional();
    }

    @Override
    public void insert(FeatureFlag f) {
        jdbc.sql("""
                insert into feature_flag (key, description, enabled, rollout_percentage, kill_switch, targeting, version,
                    updated_at, updated_by)
                values (:key, :description, :enabled, :rollout, :kill, cast(:targeting as jsonb), 0, :at, :by)
                """)
                .param("key", f.key()).param("description", f.description()).param("enabled", f.enabled())
                .param("rollout", BigDecimal.valueOf(f.rolloutPercentage())).param("kill", f.killSwitch())
                .param("targeting", json.write(f.targeting())).param("at", Timestamps.of(f.updatedAt()))
                .param("by", f.updatedBy())
                .update();
    }

    @Override
    public boolean update(FeatureFlag f) {
        return jdbc.sql("""
                update feature_flag set description = :description, enabled = :enabled, rollout_percentage = :rollout,
                    kill_switch = :kill, targeting = cast(:targeting as jsonb), version = version + 1,
                    updated_at = :at, updated_by = :by
                where key = :key and version = :version
                """)
                .param("key", f.key()).param("description", f.description()).param("enabled", f.enabled())
                .param("rollout", BigDecimal.valueOf(f.rolloutPercentage())).param("kill", f.killSwitch())
                .param("targeting", json.write(f.targeting())).param("at", Timestamps.of(f.updatedAt()))
                .param("by", f.updatedBy()).param("version", f.version())
                .update() == 1;
    }

    private FeatureFlag map(ResultSet rs, int row) throws SQLException {
        return new FeatureFlag(rs.getString("key"), rs.getString("description"), rs.getBoolean("enabled"),
                rs.getBigDecimal("rollout_percentage").doubleValue(), rs.getBoolean("kill_switch"),
                json.read(rs.getString("targeting"), FlagTargeting.class), rs.getLong("version"),
                Timestamps.instant(rs, "updated_at"), rs.getString("updated_by"));
    }
}
