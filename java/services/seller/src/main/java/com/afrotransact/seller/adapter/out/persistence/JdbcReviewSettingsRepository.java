package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.domain.AutoAssignMode;
import com.afrotransact.seller.domain.EscalationChain;
import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.port.ReviewSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReviewSettingsRepository implements ReviewSettingsRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcReviewSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ReviewSettings load() {
        return jdbc.sql("select * from review_settings where id = 1").query(this::map).optional()
                .orElseGet(ReviewSettings::defaults);
    }

    @Override
    public ReviewSettings lock() {
        return jdbc.sql("select * from review_settings where id = 1 for update").query(this::map).optional()
                .orElseGet(ReviewSettings::defaults);
    }

    @Override
    public void save(ReviewSettings s) {
        Map<String, String> cursor = new HashMap<>();
        s.roundRobinCursor().forEach((k, v) -> cursor.put(k.name(), v));
        jdbc.sql("""
                insert into review_settings (id, review_sla_hours, approval_sla_hours, due_soon_percent,
                    unassigned_after_hours, level2_after_hours, auto_assign, enforce_assignee, send_back_to_reviewer,
                    review_chain, approval_chain, round_robin_cursor, updated_by, updated_by_name, updated_at)
                values (1, :rs, :as, :ds, :ua, :l2, :mode, :enforce, :back, cast(:rc as jsonb), cast(:ac as jsonb),
                    cast(:cursor as jsonb), :by, :byName, :at)
                on conflict (id) do update set review_sla_hours = :rs, approval_sla_hours = :as,
                    due_soon_percent = :ds, unassigned_after_hours = :ua, level2_after_hours = :l2, auto_assign = :mode,
                    enforce_assignee = :enforce, send_back_to_reviewer = :back, review_chain = cast(:rc as jsonb),
                    approval_chain = cast(:ac as jsonb), round_robin_cursor = cast(:cursor as jsonb),
                    updated_by = :by, updated_by_name = :byName, updated_at = :at
                """)
                .param("rs", s.reviewSlaHours()).param("as", s.approvalSlaHours()).param("ds", s.dueSoonPercent())
                .param("ua", s.unassignedAfterHours()).param("l2", s.level2AfterHours())
                .param("mode", s.autoAssign().name()).param("enforce", s.enforceAssignee())
                .param("back", s.sendBackToReviewer()).param("rc", json.write(s.reviewChain()))
                .param("ac", json.write(s.approvalChain())).param("cursor", json.write(cursor))
                .param("by", s.updatedBy()).param("byName", s.updatedByName()).param("at", Sql.ts(s.updatedAt()))
                .update();
    }

    @Override
    public void audit(String actorId, String actorName, String reason, ReviewSettings before, ReviewSettings after) {
        jdbc.sql("""
                insert into review_settings_audit (id, actor_id, actor_name, reason, before_json, after_json, changed_at)
                values (:id, :actor, :name, :reason, cast(:before as jsonb), cast(:after as jsonb), :at)
                """)
                .param("id", UUID.randomUUID()).param("actor", actorId).param("name", actorName)
                .param("reason", reason).param("before", json.write(before)).param("after", json.write(after))
                .param("at", Sql.ts(after.updatedAt())).update();
    }

    @Override
    public List<SettingsChange> audit(int limit) {
        return jdbc.sql("select actor_name, reason, changed_at from review_settings_audit order by changed_at desc "
                + "limit :n").param("n", limit)
                .query((rs, i) -> new SettingsChange(rs.getString("actor_name"), rs.getString("reason"),
                        Sql.instant(rs, "changed_at"))).list();
    }

    private ReviewSettings map(ResultSet rs, int n) throws SQLException {
        Map<ReviewStage, String> cursor = new HashMap<>();
        json.stringMap(rs.getString("round_robin_cursor")).forEach((k, v) -> cursor.put(ReviewStage.valueOf(k), v));
        return new ReviewSettings(rs.getInt("review_sla_hours"), rs.getInt("approval_sla_hours"),
                rs.getInt("due_soon_percent"), rs.getInt("unassigned_after_hours"), rs.getInt("level2_after_hours"),
                AutoAssignMode.valueOf(rs.getString("auto_assign")), rs.getBoolean("enforce_assignee"),
                rs.getBoolean("send_back_to_reviewer"), json.read(rs.getString("review_chain"), EscalationChain.class),
                json.read(rs.getString("approval_chain"), EscalationChain.class), cursor, rs.getString("updated_by"),
                rs.getString("updated_by_name"), Sql.instant(rs, "updated_at"));
    }
}
