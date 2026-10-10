package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKind;
import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.RunArea;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.LocationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLocationRepository implements LocationRepository {

    private static final String COLUMNS = """
            slug = :slug, name = :name, market = :market, state = :state, time_zone = :tz, area_kind = :kind,
            geo_zone_id = :zone, center_lat = :clat, center_lng = :clng, radius_miles = :radius,
            origin_lat = :olat, origin_lng = :olng, default_fee_minor = :fee, currency = :currency,
            default_min_orders = :minOrders, default_capacity_orders = :capOrders,
            default_capacity_packages = :capPackages, drop_mode = :dropMode,
            pickup_point = cast(:pickup as jsonb), whatsapp_invite_url = :whatsapp,
            social_links = cast(:social as jsonb), default_cutoff_policy = :policy,
            default_extend_days = :extendDays, active = :active, updated_at = :updatedAt
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcLocationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Location insert(Location l) {
        try {
            bind(jdbc.sql("""
                    insert into location (id, slug, name, market, state, time_zone, area_kind, geo_zone_id, center_lat,
                        center_lng, radius_miles, origin_lat, origin_lng, default_fee_minor, currency,
                        default_min_orders, default_capacity_orders, default_capacity_packages, drop_mode,
                        pickup_point, whatsapp_invite_url, social_links, default_cutoff_policy, default_extend_days,
                        active, created_at, updated_at, version)
                    values (:id, :slug, :name, :market, :state, :tz, :kind, :zone, :clat, :clng, :radius, :olat, :olng,
                        :fee, :currency, :minOrders, :capOrders, :capPackages, :dropMode, cast(:pickup as jsonb),
                        :whatsapp, cast(:social as jsonb), :policy, :extendDays, :active, :createdAt, :updatedAt, 0)
                    """), l).param("createdAt", Sql.ts(l.createdAt())).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("Location slug taken: " + l.slug(), e);
        }
        return findById(l.id()).orElseThrow();
    }

    @Override
    public Location update(Location l) {
        int rows;
        try {
            rows = bind(jdbc.sql("update location set " + COLUMNS
                    + ", version = version + 1 where id = :id and version = :version"), l)
                    .param("version", l.version()).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("Location slug taken: " + l.slug(), e);
        }
        if (rows == 0) {
            throw new ConcurrentUpdateException("Location changed; reload and retry");
        }
        return findById(l.id()).orElseThrow();
    }

    @Override
    public Optional<Location> findById(UUID id) {
        return jdbc.sql("select * from location where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Location> findBySlug(String slug) {
        return jdbc.sql("select * from location where slug = :slug").param("slug", slug).query(this::map).optional();
    }

    @Override
    public List<Location> findAll(Boolean active) {
        if (active == null) {
            return jdbc.sql("select * from location order by name").query(this::map).list();
        }
        return jdbc.sql("select * from location where active = :active order by name").param("active", active)
                .query(this::map).list();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Location l) {
        LocationDetails d = l.details();
        RunArea a = d.area();
        return spec.param("id", l.id())
                .param("slug", d.slug())
                .param("name", d.name())
                .param("market", d.market())
                .param("state", d.state())
                .param("tz", d.timeZone().getId())
                .param("kind", a.kind().name())
                .param("zone", a.geoZoneId())
                .param("clat", a.center().lat())
                .param("clng", a.center().lng())
                .param("radius", a.radiusMiles())
                .param("olat", d.origin() == null ? null : d.origin().lat())
                .param("olng", d.origin() == null ? null : d.origin().lng())
                .param("fee", d.defaultFeeMinor())
                .param("currency", d.currency())
                .param("minOrders", d.defaultMinOrders())
                .param("capOrders", d.defaultCapacity().maxOrders())
                .param("capPackages", d.defaultCapacity().maxPackages())
                .param("dropMode", d.dropMode().name())
                .param("pickup", json.write(d.pickupPoint()))
                .param("whatsapp", d.whatsappInviteUrl())
                .param("social", json.write(d.socialLinks()))
                .param("policy", d.defaultCutoffPolicy().name())
                .param("extendDays", d.defaultExtendDays())
                .param("active", d.active())
                .param("updatedAt", Sql.ts(l.updatedAt()));
    }

    private Location map(ResultSet rs, int rowNum) throws SQLException {
        GeoPoint center = new GeoPoint(rs.getDouble("center_lat"), rs.getDouble("center_lng"));
        AreaKind kind = AreaKind.valueOf(rs.getString("area_kind"));
        RunArea area = new RunArea(kind, rs.getString("geo_zone_id"), center, Sql.dbl(rs, "radius_miles"));
        Double olat = Sql.dbl(rs, "origin_lat");
        Double olng = Sql.dbl(rs, "origin_lng");
        LocationDetails d = new LocationDetails(rs.getString("slug"), rs.getString("name"), rs.getString("market"),
                rs.getString("state"), ZoneId.of(rs.getString("time_zone")), area,
                olat == null || olng == null ? null : new GeoPoint(olat, olng), rs.getLong("default_fee_minor"),
                rs.getString("currency"), rs.getInt("default_min_orders"),
                new Capacity(Sql.integer(rs, "default_capacity_orders"), Sql.integer(rs, "default_capacity_packages")),
                DropMode.valueOf(rs.getString("drop_mode")), json.pickupPoint(rs.getString("pickup_point")),
                rs.getString("whatsapp_invite_url"), json.stringMap(rs.getString("social_links")),
                CutoffPolicy.valueOf(rs.getString("default_cutoff_policy")), rs.getInt("default_extend_days"),
                rs.getBoolean("active"));
        return new Location(rs.getObject("id", UUID.class), d, Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
