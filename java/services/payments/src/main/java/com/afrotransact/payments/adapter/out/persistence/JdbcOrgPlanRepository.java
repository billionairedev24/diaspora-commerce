package com.afrotransact.payments.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.domain.OrgPlan;
import com.afrotransact.payments.port.OrgPlanRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrgPlanRepository implements OrgPlanRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcOrgPlanRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<OrgPlan> find(String orgId) {
        return jdbc.sql("select * from org_plan where org_id = :org").param("org", orgId)
                .query((rs, n) -> new OrgPlan(rs.getString("org_id"), rs.getString("plan_code"),
                        rs.getString("status"), rs.getString("source"), Sql.instant(rs, "occurred_at")))
                .optional();
    }

    @Override
    public boolean upsertIfNewer(OrgPlan p) {
        // A later event replaces the row; at the same instant an event replaces a fallback read (API), never the
        // reverse.
        return jdbc.sql("""
                insert into org_plan (org_id, plan_code, status, source, occurred_at, updated_at)
                values (:org, :plan, :status, :source, :at, :now)
                on conflict (org_id) do update set plan_code = excluded.plan_code, status = excluded.status,
                    source = excluded.source, occurred_at = excluded.occurred_at, updated_at = excluded.updated_at
                 where org_plan.occurred_at < excluded.occurred_at
                    or (org_plan.occurred_at = excluded.occurred_at and org_plan.source = 'API'
                        and excluded.source = 'EVENT')
                """)
                .param("org", p.orgId()).param("plan", p.planCode()).param("status", p.status())
                .param("source", p.source()).param("at", Sql.ts(p.occurredAt())).param("now", Sql.ts(Instant.now(clock)))
                .update() == 1;
    }
}
