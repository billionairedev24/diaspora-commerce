package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.CoverageArea;
import com.afrotransact.deliveryruns.domain.Fallback;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.Placement;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcPreorderSettingsRepository implements PreorderSettingsRepository {

    private static final TypeReference<Set<Placement>> PLACEMENTS = new TypeReference<>() { };
    private static final String AREA_MATCH = "country = :country and region = :region "
            + "and lower(coalesce(city, '')) = lower(coalesce(cast(:city as varchar), ''))";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPreorderSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public PreorderSettings settings() {
        return jdbc.sql("select * from preorder_settings where id = 1").query(this::map).optional()
                .orElse(PreorderSettings.DEFAULT);
    }

    @Override
    public PreorderSettings update(PreorderSettings s) {
        int rows = jdbc.sql("""
                update preorder_settings set enabled = :enabled, fallback = :fallback,
                    placements = cast(:placements as jsonb), payment = cast(:payment as jsonb),
                    updated_at = :updatedAt, version = version + 1
                where id = 1 and version = :version
                """)
                .param("enabled", s.enabled())
                .param("fallback", s.fallback().name())
                .param("placements", json.write(s.placements().stream().sorted().map(Enum::name).toList()))
                .param("payment", json.write(s.payment()))
                .param("updatedAt", Sql.ts(s.updatedAt()))
                .param("version", s.version())
                .update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Pre-order settings changed; reload and retry");
        }
        return settings();
    }

    @Override
    public List<CoverageArea> areas() {
        return jdbc.sql("""
                select * from preorder_area order by country, region, city nulls first, lower(city)
                """).query((rs, n) -> area(rs)).list();
    }

    @Override
    public CoverageArea insertArea(AreaKey key, boolean on) {
        try {
            Instant now = Instant.now();
            jdbc.sql("""
                    insert into preorder_area (country, region, city, enabled, created_at, updated_at)
                    values (:country, :region, :city, :on, :now, :now)
                    """).param("country", key.country()).param("region", key.region()).param("city", key.city())
                    .param("on", on).param("now", Sql.ts(now)).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException(key.key() + " is already a pre-order area", e);
        }
        return new CoverageArea(key, on);
    }

    @Override
    public boolean insertAreaIfMissing(AreaKey key, boolean on) {
        Instant now = Instant.now();
        return jdbc.sql("""
                insert into preorder_area (country, region, city, enabled, created_at, updated_at)
                values (:country, :region, :city, :on, :now, :now)
                on conflict do nothing
                """).param("country", key.country()).param("region", key.region()).param("city", key.city())
                .param("on", on).param("now", Sql.ts(now)).update() == 1;
    }

    @Override
    public int setOn(Collection<AreaKey> keys, boolean on) {
        int changed = 0;
        Instant now = Instant.now();
        for (AreaKey k : keys) {
            changed += jdbc.sql("update preorder_area set enabled = :on, updated_at = :now where " + AREA_MATCH)
                    .param("on", on).param("now", Sql.ts(now)).param("country", k.country())
                    .param("region", k.region()).param("city", k.city()).update();
        }
        return changed;
    }

    @Override
    public boolean deleteArea(AreaKey key) {
        return jdbc.sql("delete from preorder_area where " + AREA_MATCH).param("country", key.country())
                .param("region", key.region()).param("city", key.city()).update() > 0;
    }

    private PreorderSettings map(ResultSet rs, int rowNum) throws SQLException {
        return new PreorderSettings(rs.getBoolean("enabled"), Fallback.valueOf(rs.getString("fallback")),
                json.read(rs.getString("placements"), PLACEMENTS),
                json.read(rs.getString("payment"), PaymentSettings.class), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private static CoverageArea area(ResultSet rs) throws SQLException {
        return new CoverageArea(new AreaKey(rs.getString("country"), rs.getString("region"), rs.getString("city")),
                rs.getBoolean("enabled"));
    }
}
