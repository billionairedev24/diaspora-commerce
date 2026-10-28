package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.PlanAssignment;
import com.afrotransact.seller.port.PlanAssignmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlanAssignmentRepository implements PlanAssignmentRepository {

    private final JdbcClient jdbc;

    JdbcPlanAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PlanAssignment a) {
        jdbc.sql("""
                insert into plan_assignment (id, org_id, from_plan, to_plan, cadence, applied, reason, assigned_by,
                    assigned_at)
                values (:id, :org, :from, :to, :cadence, :applied, :reason, :by, :at)
                """)
                .param("id", a.id()).param("org", a.orgId()).param("from", a.fromPlan()).param("to", a.toPlan())
                .param("cadence", a.cadence().name()).param("applied", a.applied()).param("reason", a.reason())
                .param("by", a.assignedBy()).param("at", Timestamp.from(a.assignedAt())).update();
    }

    @Override
    public List<PlanAssignment> ofOrg(String orgId, int limit) {
        return jdbc.sql("select * from plan_assignment where org_id = :o order by assigned_at desc limit :n")
                .param("o", orgId).param("n", limit)
                .query((rs, n) -> new PlanAssignment(rs.getObject("id", UUID.class), rs.getString("org_id"),
                        rs.getString("from_plan"), rs.getString("to_plan"), Cadence.valueOf(rs.getString("cadence")),
                        rs.getString("applied"), rs.getString("reason"), rs.getString("assigned_by"),
                        rs.getTimestamp("assigned_at").toInstant())).list();
    }
}
