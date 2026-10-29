package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.EconomicsChange;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.EconomicsSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEconomicsSettingsRepository implements EconomicsSettingsRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcEconomicsSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<EconomicsRevision> find() {
        return jdbc.sql("select * from economics_settings where id = 1").query(this::revision).optional();
    }

    @Override
    public Optional<EconomicsRevision> lock() {
        return jdbc.sql("select * from economics_settings where id = 1 for update").query(this::revision).optional();
    }

    @Override
    public void insert(EconomicsRevision r) {
        int rows = jdbc.sql("""
                insert into economics_settings (id, config, version, updated_at, updated_by, reason)
                values (1, cast(:config as jsonb), :version, :at, :by, :reason)
                on conflict (id) do nothing
                """).param("config", json.write(r.settings())).param("version", r.version())
                .param("at", Sql.ts(r.updatedAt())).param("by", r.updatedBy()).param("reason", r.reason()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("The economics settings changed; reload and retry");
        }
    }

    @Override
    public void update(EconomicsRevision r) {
        int rows = jdbc.sql("""
                update economics_settings set config = cast(:config as jsonb), version = :version, updated_at = :at,
                    updated_by = :by, reason = :reason
                where id = 1 and version = :previous
                """).param("config", json.write(r.settings())).param("version", r.version())
                .param("previous", r.version() - 1).param("at", Sql.ts(r.updatedAt())).param("by", r.updatedBy())
                .param("reason", r.reason()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("The economics settings changed; reload and retry");
        }
    }

    @Override
    public void log(EconomicsChange c) {
        jdbc.sql("""
                insert into economics_settings_change (id, version, before, after, actor, reason, changed_at)
                values (:id, :version, cast(:before as jsonb), cast(:after as jsonb), :actor, :reason, :at)
                """).param("id", c.id()).param("version", c.version()).param("before", json.write(c.before()))
                .param("after", json.write(c.after())).param("actor", c.actor()).param("reason", c.reason())
                .param("at", Sql.ts(c.at())).update();
    }

    @Override
    public List<EconomicsChange> changes(int limit) {
        return jdbc.sql("select * from economics_settings_change order by version desc limit :limit")
                .param("limit", limit).query(this::change).list();
    }

    private EconomicsRevision revision(ResultSet rs, int n) throws SQLException {
        return new EconomicsRevision(json.read(rs.getString("config"), EconomicsSettings.class), rs.getLong("version"),
                Sql.instant(rs, "updated_at"), rs.getString("updated_by"), rs.getString("reason"));
    }

    private EconomicsChange change(ResultSet rs, int n) throws SQLException {
        return new EconomicsChange(rs.getObject("id", UUID.class), rs.getLong("version"),
                json.read(rs.getString("before"), EconomicsSettings.class),
                json.read(rs.getString("after"), EconomicsSettings.class), rs.getString("actor"),
                rs.getString("reason"), Sql.instant(rs, "changed_at"));
    }
}
