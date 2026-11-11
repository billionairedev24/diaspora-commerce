package com.afrotransact.seller.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.port.SellingCaseRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellingCaseRepository implements SellingCaseRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSellingCaseRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ApplicationCase> find(String applicationId) {
        return jdbc.sql("select * from selling_case where application_id = :a").param("a", applicationId)
                .query((rs, n) -> JdbcApplicationCaseRepository.map(rs, "", json)).optional();
    }

    @Override
    public ApplicationCase save(ApplicationCase c) {
        jdbc.sql("""
                insert into selling_case (application_id, stage, assignee_id, assignee_name, assigned_by_id,
                    assigned_by_name, assigned_at, stage_entered_at, escalation_level, escalated_at, escalation_reason,
                    escalation_target_ids, escalation_target_names, returned_by_id, returned_by_name, returned_at,
                    return_note, return_count, updated_at, version)
                values (:app, :stage, :assignee, :assigneeName, :by, :byName, :assignedAt, :entered, :level,
                    :escalatedAt, :reason, cast(:targetIds as jsonb), cast(:targetNames as jsonb), :retBy, :retByName,
                    :retAt, :retNote, :retCount, :updated, 0)
                on conflict (application_id) do update set stage = excluded.stage,
                    assignee_id = excluded.assignee_id, assignee_name = excluded.assignee_name,
                    assigned_by_id = excluded.assigned_by_id, assigned_by_name = excluded.assigned_by_name,
                    assigned_at = excluded.assigned_at, stage_entered_at = excluded.stage_entered_at,
                    escalation_level = excluded.escalation_level, escalated_at = excluded.escalated_at,
                    escalation_reason = excluded.escalation_reason,
                    escalation_target_ids = excluded.escalation_target_ids,
                    escalation_target_names = excluded.escalation_target_names,
                    returned_by_id = excluded.returned_by_id, returned_by_name = excluded.returned_by_name,
                    returned_at = excluded.returned_at, return_note = excluded.return_note,
                    return_count = excluded.return_count, updated_at = excluded.updated_at,
                    version = selling_case.version + 1
                """)
                .param("app", c.applicationId()).param("stage", c.stage() == null ? null : c.stage().name())
                .param("assignee", c.assigneeId()).param("assigneeName", c.assigneeName())
                .param("by", c.assignedById()).param("byName", c.assignedByName())
                .param("assignedAt", Sql.ts(c.assignedAt())).param("entered", Sql.ts(c.stageEnteredAt()))
                .param("level", c.escalationLevel()).param("escalatedAt", Sql.ts(c.escalatedAt()))
                .param("reason", c.escalationReason()).param("targetIds", json.write(c.escalationTargetIds()))
                .param("targetNames", json.write(c.escalationTargetNames())).param("retBy", c.returnedById())
                .param("retByName", c.returnedByName()).param("retAt", Sql.ts(c.returnedAt()))
                .param("retNote", c.returnNote()).param("retCount", c.returnCount())
                .param("updated", Sql.ts(c.updatedAt())).update();
        return find(c.applicationId()).orElseThrow();
    }

    @Override
    public List<String> escalationCandidates(int limit) {
        return jdbc.sql("""
                select c.application_id from selling_case c join selling_application a on a.id = c.application_id
                where c.stage is not null and c.escalation_level < 2
                  and a.status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL')
                order by c.stage_entered_at limit :n
                """).param("n", limit).query(String.class).list();
    }

    @Override
    public Map<String, Integer> openLoad(ReviewStage stage) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("""
                select c.assignee_id, count(*) as n from selling_case c
                join selling_application a on a.id = c.application_id
                where c.stage = :s and c.assignee_id is not null
                  and a.status in ('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL')
                group by c.assignee_id
                """).param("s", stage.name()).query((rs, i) -> {
                    out.put(rs.getString("assignee_id"), rs.getInt("n"));
                    return null;
                }).list();
        return out;
    }
}
