package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.FulfilmentCentre;
import com.afrotransact.fulfilment.port.CentreRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCentreRepository implements CentreRepository {

    private final JdbcClient jdbc;

    JdbcCentreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FulfilmentCentre save(FulfilmentCentre c) {
        jdbc.sql("""
                insert into fulfilment_centre (id, name, kind, timezone, address_line, city, state, postal_code,
                    country, lat, lon, cold_chain, active, updated_at)
                values (:id, :name, :kind, :tz, :line, :city, :state, :zip, :country, :lat, :lon, :cold, :active,
                    now())
                on conflict (id) do update set name = excluded.name, kind = excluded.kind,
                    timezone = excluded.timezone, address_line = excluded.address_line, city = excluded.city,
                    state = excluded.state, postal_code = excluded.postal_code, country = excluded.country,
                    lat = excluded.lat, lon = excluded.lon, cold_chain = excluded.cold_chain,
                    active = excluded.active, updated_at = now()
                """)
                .param("id", c.id()).param("name", c.name()).param("kind", c.kind()).param("tz", c.timezone())
                .param("line", c.addressLine()).param("city", c.city()).param("state", c.state())
                .param("zip", c.postalCode()).param("country", c.country())
                .param("lat", Rows.lat(c.location())).param("lon", Rows.lon(c.location()))
                .param("cold", c.coldChain()).param("active", c.active())
                .update();
        return c;
    }

    @Override
    public Optional<FulfilmentCentre> find(String id) {
        return jdbc.sql("select * from fulfilment_centre where id = :id").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public List<FulfilmentCentre> list() {
        return jdbc.sql("select * from fulfilment_centre order by id").query(this::map).list();
    }

    private FulfilmentCentre map(ResultSet rs, int n) throws SQLException {
        return new FulfilmentCentre(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                rs.getString("timezone"), rs.getString("address_line"), rs.getString("city"), rs.getString("state"),
                rs.getString("postal_code"), rs.getString("country"), Rows.point(rs, "lat", "lon"),
                rs.getBoolean("cold_chain"), rs.getBoolean("active"));
    }
}
