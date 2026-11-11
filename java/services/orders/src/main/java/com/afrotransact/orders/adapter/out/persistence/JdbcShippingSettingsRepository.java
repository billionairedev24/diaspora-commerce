package com.afrotransact.orders.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsChange;
import com.afrotransact.orders.port.ShippingSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** shipping_settings (one row per currency) and shipping_settings_change (the audit trail). */
@Repository
class JdbcShippingSettingsRepository implements ShippingSettingsRepository {

    private static final String COLUMNS = """
            currency, version, flat_fee_minor, free_above_minor, seller_carrier_shipping_enabled, updated_at,
            updated_by, reason""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcShippingSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ShippingSettings> find(String currency) {
        return jdbc.sql("select " + COLUMNS + " from shipping_settings where currency = :c").param("c", currency)
                .query(JdbcShippingSettingsRepository::read).optional();
    }

    @Override
    public Optional<ShippingSettings> lock(String currency) {
        return jdbc.sql("select " + COLUMNS + " from shipping_settings where currency = :c for update")
                .param("c", currency).query(JdbcShippingSettingsRepository::read).optional();
    }

    @Override
    public void update(ShippingSettings s) {
        jdbc.sql("""
                update shipping_settings set version = :version, flat_fee_minor = :fee, free_above_minor = cast(:free as bigint),
                    seller_carrier_shipping_enabled = :carrier, updated_at = :at, updated_by = :by, reason = :reason
                where currency = :currency
                """)
                .param("version", s.version()).param("fee", s.flatFeeMinor()).param("free", s.freeAboveMinor())
                .param("carrier", s.sellerCarrierShippingEnabled()).param("at", Sql.ts(s.updatedAt()))
                .param("by", s.updatedBy()).param("reason", s.reason()).param("currency", s.currency()).update();
    }

    @Override
    public void insert(ShippingSettings s) {
        jdbc.sql("""
                insert into shipping_settings (currency, version, flat_fee_minor, free_above_minor,
                    seller_carrier_shipping_enabled, updated_at, updated_by, reason)
                values (:currency, :version, :fee, cast(:free as bigint), :carrier, :at, :by, :reason)
                """)
                .param("currency", s.currency()).param("version", s.version()).param("fee", s.flatFeeMinor())
                .param("free", s.freeAboveMinor()).param("carrier", s.sellerCarrierShippingEnabled())
                .param("at", Sql.ts(s.updatedAt())).param("by", s.updatedBy()).param("reason", s.reason()).update();
    }

    @Override
    public void log(ShippingSettingsChange c) {
        jdbc.sql("""
                insert into shipping_settings_change (id, currency, version, before, after, actor_id, reason,
                    changed_at)
                values (:id, :currency, :version, cast(:before as jsonb), cast(:after as jsonb), :actor, :reason, :at)
                """)
                .param("id", c.id()).param("currency", c.currency()).param("version", c.version())
                .param("before", c.before() == null ? null : json.write(Snapshot.of(c.before())))
                .param("after", json.write(Snapshot.of(c.after()))).param("actor", c.actorId())
                .param("reason", c.reason()).param("at", Sql.ts(c.changedAt())).update();
    }

    @Override
    public List<ShippingSettingsChange> changes(String currency, int limit) {
        return jdbc.sql("""
                select id, currency, version, before, after, actor_id, reason, changed_at
                from shipping_settings_change where currency = :c order by version desc limit :limit
                """)
                .param("c", currency).param("limit", limit)
                .query((rs, n) -> new ShippingSettingsChange(UUID.fromString(rs.getString("id")),
                        rs.getString("currency"), rs.getLong("version"), parse(rs.getString("before"), rs),
                        parse(rs.getString("after"), rs), rs.getString("actor_id"), rs.getString("reason"),
                        Sql.instant(rs, "changed_at")))
                .list();
    }

    /** The fields of a record as the change log keeps them. */
    record Snapshot(long flatFeeMinor, Long freeAboveMinor, boolean sellerCarrierShippingEnabled) {

        static Snapshot of(ShippingSettings s) {
            return new Snapshot(s.flatFeeMinor(), s.freeAboveMinor(), s.sellerCarrierShippingEnabled());
        }
    }

    private ShippingSettings parse(String doc, ResultSet rs) throws SQLException {
        Snapshot n = json.read(doc, Snapshot.class);
        if (n == null) {
            return null;
        }
        return new ShippingSettings(rs.getString("currency"), n.flatFeeMinor(), n.freeAboveMinor(),
                n.sellerCarrierShippingEnabled(), rs.getLong("version"), Sql.instant(rs, "changed_at"),
                rs.getString("actor_id"), rs.getString("reason"));
    }

    private static ShippingSettings read(ResultSet rs, int n) throws SQLException {
        return new ShippingSettings(rs.getString("currency"), rs.getLong("flat_fee_minor"),
                rs.getObject("free_above_minor", Long.class), rs.getBoolean("seller_carrier_shipping_enabled"), rs.getLong("version"),
                Sql.instant(rs, "updated_at"), rs.getString("updated_by"), rs.getString("reason"));
    }
}
