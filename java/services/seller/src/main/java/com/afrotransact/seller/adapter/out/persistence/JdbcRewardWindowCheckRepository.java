package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.seller.port.RewardWindowCheckRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRewardWindowCheckRepository implements RewardWindowCheckRepository {

    private final JdbcClient jdbc;

    JdbcRewardWindowCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertOnce(Check c) {
        return jdbc.sql("""
                insert into reward_window_check (rule_id, org_id, window_start, window_end, checked_at, value,
                    threshold, comparison, outcome, reason, grant_id)
                values (:rule, :org, :start, :end, :at, :value, :threshold, :comparison, :outcome, :reason, :grant)
                on conflict (rule_id, org_id, window_start) do nothing
                """).param("rule", c.ruleId()).param("org", c.orgId()).param("start", Date.valueOf(c.windowStart()))
                .param("end", Sql.ts(c.windowEnd())).param("at", Sql.ts(c.checkedAt())).param("value", c.value())
                .param("threshold", c.threshold()).param("comparison", c.comparison().name())
                .param("outcome", c.outcome()).param("reason", c.reason()).param("grant", c.grantId())
                .update() == 1;
    }

    @Override
    public boolean exists(UUID ruleId, String orgId, LocalDate windowStart) {
        return jdbc.sql("select count(*) from reward_window_check where rule_id = :r and org_id = :o "
                + "and window_start = :s").param("r", ruleId).param("o", orgId)
                .param("s", Date.valueOf(windowStart)).query(Long.class).single() > 0;
    }
}
