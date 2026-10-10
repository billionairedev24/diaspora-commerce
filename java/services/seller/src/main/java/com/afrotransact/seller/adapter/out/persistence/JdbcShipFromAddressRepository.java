package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.AfrotransactPickup;
import com.afrotransact.seller.domain.GeoPosition;
import com.afrotransact.seller.domain.PickupWindow;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcShipFromAddressRepository implements ShipFromAddressRepository {

    private static final TypeReference<List<WindowRow>> WINDOWS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcShipFromAddressRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Stored form of a pickup window. */
    record WindowRow(String day, String opens, String closes) {
    }

    @Override
    public List<ShipFromAddress> list(String orgId) {
        return jdbc.sql("select * from ship_from_address where org_id = :o order by is_default desc, created_at, id")
                .param("o", orgId).query(this::map).list();
    }

    @Override
    public Optional<ShipFromAddress> find(String orgId, UUID id) {
        return jdbc.sql("select * from ship_from_address where org_id = :o and id = :id").param("o", orgId)
                .param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<ShipFromAddress> findById(UUID id) {
        return jdbc.sql("select * from ship_from_address where id = :id").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public void insert(ShipFromAddress a) {
        jdbc.sql("""
                insert into ship_from_address (id, org_id, label, name, company, phone, line1, line2, city, state,
                                               postal_code, country, is_default, created_at, updated_at, version,
                                               latitude, longitude, geocode_precision, geocoded_at,
                                               afrotransact_pickup, pickup_hours, pickup_zone, pickup_enabled_at)
                values (:id, :org, :label, :name, :company, :phone, :line1, :line2, :city, :state, :zip, :country,
                        :def, :created, :updated, 0, :lat, :lng, :precision, :geocodedAt, :pickup,
                        cast(:hours as jsonb), :zone, :enabledAt)
                """)
                .param("id", a.id()).param("org", a.orgId()).param("label", a.label()).param("name", a.name())
                .param("company", a.company()).param("phone", a.phone()).param("line1", a.line1())
                .param("line2", a.line2()).param("city", a.city()).param("state", a.state())
                .param("zip", a.postalCode()).param("country", a.country()).param("def", a.isDefault())
                .param("created", Sql.ts(a.createdAt())).param("updated", Sql.ts(a.updatedAt()))
                .param("lat", a.position() == null ? null : a.position().lat())
                .param("lng", a.position() == null ? null : a.position().lng())
                .param("precision", a.position() == null ? null : a.position().precision())
                .param("geocodedAt", a.position() == null ? null : Sql.ts(a.position().geocodedAt()))
                .param("pickup", a.pickup().enabled()).param("hours", hoursJson(a))
                .param("zone", a.pickup().zoneName()).param("enabledAt", Sql.ts(a.pickup().enabledAt()))
                .update();
    }

    @Override
    public boolean update(ShipFromAddress a) {
        return jdbc.sql("""
                update ship_from_address set label = :label, name = :name, company = :company, phone = :phone,
                       line1 = :line1, line2 = :line2, city = :city, state = :state, postal_code = :zip,
                       country = :country, updated_at = :updated, version = version + 1, latitude = :lat,
                       longitude = :lng, geocode_precision = :precision, geocoded_at = :geocodedAt,
                       afrotransact_pickup = :pickup, pickup_hours = cast(:hours as jsonb), pickup_zone = :zone,
                       pickup_enabled_at = :enabledAt
                 where org_id = :org and id = :id and version = :version
                """)
                .param("id", a.id()).param("org", a.orgId()).param("label", a.label()).param("name", a.name())
                .param("company", a.company()).param("phone", a.phone()).param("line1", a.line1())
                .param("line2", a.line2()).param("city", a.city()).param("state", a.state())
                .param("zip", a.postalCode()).param("country", a.country())
                .param("updated", Sql.ts(a.updatedAt())).param("version", a.version())
                .param("lat", a.position() == null ? null : a.position().lat())
                .param("lng", a.position() == null ? null : a.position().lng())
                .param("precision", a.position() == null ? null : a.position().precision())
                .param("geocodedAt", a.position() == null ? null : Sql.ts(a.position().geocodedAt()))
                .param("pickup", a.pickup().enabled()).param("hours", hoursJson(a))
                .param("zone", a.pickup().zoneName()).param("enabledAt", Sql.ts(a.pickup().enabledAt()))
                .update() == 1;
    }

    @Override
    public void makeDefault(String orgId, UUID id) {
        jdbc.sql("update ship_from_address set is_default = false, version = version + 1 where org_id = :o and "
                + "is_default and id <> :id").param("o", orgId).param("id", id).update();
        jdbc.sql("update ship_from_address set is_default = true, version = version + 1 where org_id = :o and "
                + "id = :id and not is_default").param("o", orgId).param("id", id).update();
    }

    @Override
    public void delete(String orgId, UUID id) {
        jdbc.sql("delete from ship_from_address where org_id = :o and id = :id").param("o", orgId).param("id", id)
                .update();
    }

    private ShipFromAddress map(ResultSet rs, int n) throws SQLException {
        double lat = rs.getDouble("latitude");
        boolean noPosition = rs.wasNull();
        double lng = rs.getDouble("longitude");
        noPosition = noPosition || rs.wasNull();
        GeoPosition position = noPosition ? null : new GeoPosition(lat, lng,
                rs.getString("geocode_precision") == null ? "ADDRESS" : rs.getString("geocode_precision"),
                Sql.instant(rs, "geocoded_at"));
        List<PickupWindow> hours = json.read(rs.getString("pickup_hours"), WINDOWS).stream()
                .map(w -> new PickupWindow(DayOfWeek.valueOf(w.day()), LocalTime.parse(w.opens()),
                        LocalTime.parse(w.closes()))).toList();
        AfrotransactPickup pickup = new AfrotransactPickup(rs.getBoolean("afrotransact_pickup"), hours,
                rs.getString("pickup_zone"), Sql.instant(rs, "pickup_enabled_at"));
        return new ShipFromAddress(rs.getObject("id", UUID.class), rs.getString("org_id"), rs.getString("label"),
                rs.getString("name"), rs.getString("company"), rs.getString("phone"), rs.getString("line1"),
                rs.getString("line2"), rs.getString("city"), rs.getString("state"), rs.getString("postal_code"),
                rs.getString("country"), rs.getBoolean("is_default"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"), position, pickup);
    }

    private String hoursJson(ShipFromAddress a) {
        return json.write(a.pickup().hours().stream().map(w -> new WindowRow(w.day().name(), w.opens().toString(),
                w.closes().toString())).toList());
    }
}
