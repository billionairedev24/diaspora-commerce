package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.bookings.domain.ServiceArea;
import com.afrotransact.bookings.port.ServiceAreaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcServiceAreaRepository implements ServiceAreaRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcServiceAreaRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ServiceArea> find(String orgId) {
        return jdbc.sql("select * from provider_service_area where org_id = :org").param("org", orgId)
                .query(this::map).optional();
    }

    @Override
    public ServiceArea save(ServiceArea a) {
        jdbc.sql("""
                insert into provider_service_area (org_id, base_zip, base_city, base_state, lat, lon, radius_miles,
                                                   extra_areas, served_cities, updated_at)
                values (:org, :zip, :city, :state, :lat, :lon, :radius, cast(:extra as jsonb),
                        cast(:served as jsonb), :at)
                on conflict (org_id) do update set base_zip = :zip, base_city = :city, base_state = :state,
                       lat = :lat, lon = :lon, radius_miles = :radius, extra_areas = cast(:extra as jsonb),
                       served_cities = cast(:served as jsonb), updated_at = :at
                """)
                .param("org", a.orgId()).param("zip", a.baseZip()).param("city", a.baseCity())
                .param("state", a.baseState()).param("lat", a.lat()).param("lon", a.lon())
                .param("radius", a.radiusMiles()).param("extra", json.write(a.extraAreas()))
                .param("served", json.write(a.servedCities())).param("at", Sql.ts(a.updatedAt()))
                .update();
        return find(a.orgId()).orElseThrow();
    }

    @Override
    public Map<String, ServiceArea> byOrgs(Collection<String> orgIds) {
        Map<String, ServiceArea> out = new HashMap<>();
        if (orgIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select * from provider_service_area where org_id in (:orgs)").param("orgs", orgIds.stream()
                .toList()).query(this::map).list().forEach(a -> out.put(a.orgId(), a));
        return out;
    }

    private ServiceArea map(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceArea(rs.getString("org_id"), rs.getString("base_zip"), rs.getString("base_city"),
                rs.getString("base_state"), rs.getDouble("lat"), rs.getDouble("lon"), rs.getInt("radius_miles"),
                json.namedAreas(rs.getString("extra_areas")), json.zoneCities(rs.getString("served_cities")),
                rs.getTimestamp("updated_at").toInstant());
    }
}
