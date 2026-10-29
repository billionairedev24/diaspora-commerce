package com.afrotransact.seller.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.seller.port.PlanPolicyStateRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanPolicyStateRepository implements PlanPolicyStateRepository {

    private final JdbcClient jdbc;

    JdbcPlanPolicyStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> metAt(String orgId) {
        return jdbc.sql("select met_at from plan_policy_state where org_id = :org").param("org", orgId)
                .query((rs, n) -> Sql.instant(rs, "met_at")).optional();
    }

    @Override
    public void markMet(String orgId, Instant at) {
        jdbc.sql("insert into plan_policy_state (org_id, met_at) values (:org, :at) on conflict (org_id) do nothing")
                .param("org", orgId).param("at", Sql.ts(at)).update();
    }
}
