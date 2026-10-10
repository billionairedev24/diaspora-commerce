package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Optional;

import com.afrotransact.bookings.domain.BookingRules;
import com.afrotransact.bookings.domain.CancellationPolicy;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProviderRepository implements ProviderRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcProviderRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Provider> find(String orgId) {
        return jdbc.sql("select * from provider where org_id = :id").param("id", orgId).query(this::map).optional();
    }

    @Override
    public Optional<Provider> lock(String orgId) {
        return jdbc.sql("select * from provider where org_id = :id for update").param("id", orgId).query(this::map)
                .optional();
    }

    @Override
    public Provider insert(Provider p) {
        jdbc.sql("""
                insert into provider (org_id, display_name, time_zone, currency, location, rules, policy, strikes,
                                      created_at, updated_at, version)
                values (:org, :name, :zone, :currency, :location, cast(:rules as jsonb), cast(:policy as jsonb),
                        :strikes, :createdAt, :updatedAt, 0)
                """)
                .param("org", p.orgId())
                .param("name", p.displayName())
                .param("zone", p.timeZone().getId())
                .param("currency", p.currency())
                .param("location", p.location())
                .param("rules", json.write(p.rules()))
                .param("policy", json.write(p.policy()))
                .param("strikes", p.strikes())
                .param("createdAt", Sql.ts(p.createdAt()))
                .param("updatedAt", Sql.ts(p.updatedAt()))
                .update();
        return find(p.orgId()).orElseThrow();
    }

    @Override
    public Provider update(Provider p) {
        int rows = jdbc.sql("""
                update provider set display_name = :name, time_zone = :zone, location = :location,
                       rules = cast(:rules as jsonb), policy = cast(:policy as jsonb), strikes = :strikes,
                       updated_at = :updatedAt, version = version + 1
                where org_id = :org and version = :version
                """)
                .param("name", p.displayName())
                .param("zone", p.timeZone().getId())
                .param("location", p.location())
                .param("rules", json.write(p.rules()))
                .param("policy", json.write(p.policy()))
                .param("strikes", p.strikes())
                .param("updatedAt", Sql.ts(p.updatedAt()))
                .param("org", p.orgId())
                .param("version", p.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("bookings.concurrent-update", "Provider setup changed; reload and retry");
        }
        return find(p.orgId()).orElseThrow();
    }

    private Provider map(ResultSet rs, int rowNum) throws SQLException {
        return new Provider(rs.getString("org_id"), rs.getString("display_name"),
                ZoneId.of(rs.getString("time_zone")), rs.getString("currency"), rs.getString("location"),
                json.read(rs.getString("rules"), BookingRules.class),
                json.read(rs.getString("policy"), CancellationPolicy.class), rs.getInt("strikes"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
