package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunAction;
import com.afrotransact.deliveryruns.domain.RunActionKind;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.RunActionLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunActionLog implements RunActionLog {

    private final JdbcClient jdbc;

    JdbcRunActionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(RunAction a) {
        jdbc.sql("""
                insert into run_action_log (id, run_id, kind, reason, actor, from_status, to_status, orders, minimum,
                    from_cutoff, to_cutoff, acted_at)
                values (:id, :run, :kind, :reason, :actor, :from, :to, :orders, :minimum, :fromCutoff, :toCutoff, :at)
                """).param("id", a.id()).param("run", a.runId()).param("kind", a.kind().name())
                .param("reason", a.reason()).param("actor", a.actor()).param("from", a.fromStatus().name())
                .param("to", a.toStatus().name()).param("orders", a.orders()).param("minimum", a.minimum())
                .param("fromCutoff", Sql.ts(a.fromCutoff())).param("toCutoff", Sql.ts(a.toCutoff()))
                .param("at", Sql.ts(a.at())).update();
    }

    @Override
    public List<RunAction> findByRun(UUID runId) {
        return jdbc.sql("select * from run_action_log where run_id = :r order by acted_at, seq").param("r", runId)
                .query((rs, n) -> new RunAction(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        RunActionKind.valueOf(rs.getString("kind")), rs.getString("reason"), rs.getString("actor"),
                        RunStatus.valueOf(rs.getString("from_status")), RunStatus.valueOf(rs.getString("to_status")),
                        rs.getInt("orders"), rs.getInt("minimum"), Sql.instant(rs, "from_cutoff"),
                        Sql.instant(rs, "to_cutoff"), Sql.instant(rs, "acted_at")))
                .list();
    }
}
