package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DecisionKind;
import com.afrotransact.deliveryruns.domain.WindowDecision;
import com.afrotransact.deliveryruns.port.PreorderDecisionLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPreorderDecisionLog implements PreorderDecisionLog {

    private final JdbcClient jdbc;

    JdbcPreorderDecisionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(WindowDecision d) {
        jdbc.sql("""
                insert into preorder_window_decision (id, window_id, kind, reason, actor, orders, minimum, decided_at)
                values (:id, :window, :kind, :reason, :actor, :orders, :minimum, :at)
                """).param("id", d.id()).param("window", d.windowId()).param("kind", d.kind().name())
                .param("reason", d.reason()).param("actor", d.actor()).param("orders", d.orders())
                .param("minimum", d.minimum()).param("at", Sql.ts(d.at())).update();
    }

    @Override
    public List<WindowDecision> findByWindow(UUID windowId) {
        return jdbc.sql("select * from preorder_window_decision where window_id = :w order by decided_at, id")
                .param("w", windowId)
                .query((rs, n) -> new WindowDecision(rs.getObject("id", UUID.class),
                        rs.getObject("window_id", UUID.class), DecisionKind.valueOf(rs.getString("kind")),
                        rs.getString("reason"), rs.getString("actor"), rs.getInt("orders"), rs.getInt("minimum"),
                        Sql.instant(rs, "decided_at")))
                .list();
    }
}
