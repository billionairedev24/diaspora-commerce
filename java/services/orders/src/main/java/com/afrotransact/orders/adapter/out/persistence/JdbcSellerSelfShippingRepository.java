package com.afrotransact.orders.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.afrotransact.orders.domain.SellerSelfShipping;
import com.afrotransact.orders.port.SellerSelfShippingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** seller_self_shipping: one flat per-order charge per seller organisation. */
@Repository
class JdbcSellerSelfShippingRepository implements SellerSelfShippingRepository {

    private static final String COLUMNS = "seller_org_id, currency, charge_minor, enabled, version, updated_at, updated_by";

    private final JdbcClient jdbc;

    JdbcSellerSelfShippingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SellerSelfShipping> find(String sellerOrgId) {
        return jdbc.sql("select " + COLUMNS + " from seller_self_shipping where seller_org_id = :org")
                .param("org", sellerOrgId).query(JdbcSellerSelfShippingRepository::read).optional();
    }

    @Override
    public List<SellerSelfShipping> findAll(Collection<String> sellerOrgIds) {
        if (sellerOrgIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + " from seller_self_shipping where seller_org_id in (:orgs)")
                .param("orgs", sellerOrgIds).query(JdbcSellerSelfShippingRepository::read).list();
    }

    @Override
    public void save(SellerSelfShipping r) {
        jdbc.sql("""
                insert into seller_self_shipping (seller_org_id, currency, charge_minor, enabled, version, updated_at,
                    updated_by)
                values (:org, :currency, :charge, :enabled, :version, :at, :by)
                on conflict (seller_org_id) do update set currency = excluded.currency,
                    charge_minor = excluded.charge_minor, enabled = excluded.enabled, version = excluded.version,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """)
                .param("org", r.sellerOrgId()).param("currency", r.currency()).param("charge", r.chargeMinor())
                .param("enabled", r.enabled()).param("version", r.version()).param("at", Sql.ts(r.updatedAt()))
                .param("by", r.updatedBy()).update();
    }

    private static SellerSelfShipping read(ResultSet rs, int n) throws SQLException {
        return new SellerSelfShipping(rs.getString("seller_org_id"), rs.getString("currency"),
                rs.getLong("charge_minor"), rs.getBoolean("enabled"), rs.getLong("version"),
                Sql.instant(rs, "updated_at"), rs.getString("updated_by"));
    }
}
