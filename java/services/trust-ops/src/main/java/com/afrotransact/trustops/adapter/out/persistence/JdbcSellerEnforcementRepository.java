package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.trustops.domain.EnforcementStatus;
import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SuspensionReason;
import com.afrotransact.trustops.port.SellerEnforcementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerEnforcementRepository implements SellerEnforcementRepository {

    private final JdbcClient jdbc;

    JdbcSellerEnforcementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SellerEnforcement> find(String orgId) {
        return jdbc.sql("select * from seller_enforcement where org_id = :org").param("org", orgId)
                .query(JdbcSellerEnforcementRepository::map).optional();
    }

    @Override
    public SellerEnforcement save(SellerEnforcement e) {
        if (e.isNew()) {
            try {
                jdbc.sql("""
                        insert into seller_enforcement (org_id, status, reason_code, note, actor_id, changed_at,
                            payouts_held, buy_box_restricted, version)
                        values (:org, :status, :reason, :note, :actor, :changedAt, :held, :restricted, 0)
                        """)
                        .params(params(e))
                        .update();
            } catch (DuplicateKeyException ex) {
                throw conflict();
            }
        } else {
            int rows = jdbc.sql("""
                    update seller_enforcement set status = :status, reason_code = :reason, note = :note,
                        actor_id = :actor, changed_at = :changedAt, payouts_held = :held,
                        buy_box_restricted = :restricted, version = version + 1
                    where org_id = :org and version = :version
                    """)
                    .params(params(e))
                    .param("version", e.version())
                    .update();
            if (rows == 0) {
                throw conflict();
            }
        }
        return find(e.orgId()).orElseThrow();
    }

    @Override
    public List<SellerEnforcement> suspended() {
        return jdbc.sql("select * from seller_enforcement where status = 'SUSPENDED' order by changed_at desc")
                .query(JdbcSellerEnforcementRepository::map).list();
    }

    private static Map<String, Object> params(SellerEnforcement e) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", e.orgId());
        p.put("status", e.status().name());
        p.put("reason", e.reason() == null ? null : e.reason().name());
        p.put("note", e.note());
        p.put("actor", e.actorId());
        p.put("changedAt", Sql.ts(e.changedAt()));
        p.put("held", e.payoutsHeld());
        p.put("restricted", e.buyBoxRestricted());
        return p;
    }

    private static ConflictException conflict() {
        return new ConflictException("trustops.concurrent-update", "The seller's standing changed; reload and retry");
    }

    private static SellerEnforcement map(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("reason_code");
        return new SellerEnforcement(rs.getString("org_id"), EnforcementStatus.valueOf(rs.getString("status")),
                reason == null ? null : SuspensionReason.valueOf(reason), rs.getString("note"),
                rs.getString("actor_id"), Sql.instant(rs, "changed_at"), rs.getBoolean("payouts_held"),
                rs.getBoolean("buy_box_restricted"), rs.getLong("version"));
    }
}
