package com.afrotransact.offers.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.offers.domain.SellerStanding;
import com.afrotransact.offers.port.SellerStandingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerStandingRepository implements SellerStandingRepository {

    private final JdbcClient jdbc;

    JdbcSellerStandingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SellerStanding> find(String orgId) {
        return jdbc.sql("select * from seller_standing where org_id = :org").param("org", orgId)
                .query(JdbcSellerStandingRepository::map).optional();
    }

    @Override
    public Map<String, SellerStanding> findAll(Collection<String> orgIds) {
        Map<String, SellerStanding> out = new HashMap<>();
        if (orgIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select * from seller_standing where org_id in (:orgs)").param("orgs", List.copyOf(orgIds))
                .query(JdbcSellerStandingRepository::map).list()
                .forEach(s -> out.put(s.orgId(), s));
        return out;
    }

    @Override
    public SellerStanding save(SellerStanding s) {
        jdbc.sql("""
                insert into seller_standing (org_id, display_name, rating, on_time_rate, defect_rate, restricted,
                                             listings_hidden, updated_at)
                values (:org, :name, :rating, :onTime, :defect, :restricted, :hidden, :updatedAt)
                on conflict (org_id) do update
                   set display_name = excluded.display_name, rating = excluded.rating,
                       on_time_rate = excluded.on_time_rate, defect_rate = excluded.defect_rate,
                       restricted = excluded.restricted, listings_hidden = excluded.listings_hidden,
                       updated_at = excluded.updated_at
                """)
                .param("org", s.orgId())
                .param("name", s.displayName())
                .param("rating", decimal(s.rating()))
                .param("onTime", decimal(s.onTimeRate()))
                .param("defect", decimal(s.defectRate()))
                .param("restricted", s.restricted())
                .param("hidden", s.listingsHidden())
                .param("updatedAt", s.updatedAt() == null ? null : Timestamp.from(s.updatedAt()))
                .update();
        return find(s.orgId()).orElseThrow();
    }

    private static BigDecimal decimal(Double d) {
        return d == null ? null : BigDecimal.valueOf(d);
    }

    private static Double number(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }

    private static SellerStanding map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new SellerStanding(rs.getString("org_id"), rs.getString("display_name"), number(rs, "rating"),
                number(rs, "on_time_rate"), number(rs, "defect_rate"), rs.getBoolean("restricted"),
                rs.getBoolean("listings_hidden"), updated == null ? null : updated.toInstant());
    }
}
