package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanRepository implements PlanRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPlanRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Plan> findAll(OrgKind kind) {
        String where = kind == null ? "" : " and kind = :kind";
        JdbcClient.StatementSpec spec = jdbc.sql("select * from plan where active" + where
                + " order by kind desc, sort_order");
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        return spec.query(this::map).list();
    }

    @Override
    public Optional<Plan> find(String code) {
        return jdbc.sql("select * from plan where code = :c").param("c", code).query(this::map).optional();
    }

    @Override
    public String stripeLookupKey(String planCode, Cadence cadence) {
        return jdbc.sql("select stripe_lookup_key from plan_price where plan_code = :p and cadence = :c")
                .param("p", planCode).param("c", cadence.name()).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("seller.price-not-found",
                        "No Stripe price for " + planCode + " " + cadence));
    }

    @Override
    public Optional<PriceRef> byLookupKey(String lookupKey) {
        if (lookupKey == null) {
            return Optional.empty();
        }
        return jdbc.sql("select plan_code, cadence from plan_price where stripe_lookup_key = :k").param("k", lookupKey)
                .query((rs, n) -> new PriceRef(rs.getString("plan_code"), Cadence.valueOf(rs.getString("cadence"))))
                .optional();
    }

    private Plan map(ResultSet rs, int n) throws SQLException {
        return new Plan(rs.getString("code"), OrgKind.valueOf(rs.getString("kind")), rs.getString("name"),
                rs.getString("tagline"), rs.getLong("monthly_cents"), rs.getInt("trial_days"),
                json.strings(rs.getString("features")), rs.getInt("sort_order"));
    }
}
