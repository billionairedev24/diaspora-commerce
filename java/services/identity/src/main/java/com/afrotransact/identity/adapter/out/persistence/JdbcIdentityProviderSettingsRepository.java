package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.IdentityProviderSetting;
import com.afrotransact.identity.port.IdentityProviderSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdentityProviderSettingsRepository implements IdentityProviderSettingsRepository {

    private final JdbcClient jdbc;

    JdbcIdentityProviderSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IdentityProviderSetting> all() {
        return jdbc.sql("select * from identity_provider order by position")
                .query(JdbcIdentityProviderSettingsRepository::map).list();
    }

    @Override
    public Optional<IdentityProviderSetting> find(String id) {
        return jdbc.sql("select * from identity_provider where id = :id").param("id", id)
                .query(JdbcIdentityProviderSettingsRepository::map).optional();
    }

    @Override
    public void save(IdentityProviderSetting s) {
        jdbc.sql("""
                update identity_provider set enabled = :enabled, updated_at = :at, updated_by = :by where id = :id
                """)
                .param("id", s.id()).param("enabled", s.enabled()).param("at", Jdbc.ts(s.updatedAt()))
                .param("by", s.updatedBy())
                .update();
    }

    private static IdentityProviderSetting map(ResultSet rs, int n) throws SQLException {
        return new IdentityProviderSetting(rs.getString("id"), rs.getString("display_name"),
                rs.getBoolean("enabled"), rs.getInt("position"), Jdbc.instant(rs, "updated_at"),
                rs.getString("updated_by"));
    }
}
