package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;
import com.afrotransact.payments.port.ReconciliationItemRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReconciliationItemRepository implements ReconciliationItemRepository {

    private final JdbcClient jdbc;

    JdbcReconciliationItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ReconciliationItem i) {
        jdbc.sql("""
                insert into reconciliation_item (id, run_id, window_start, stripe_ref, currency, ledger_minor,
                    stripe_minor, status, note, resolved_by, resolved_at, created_at)
                values (:id, :run, :window, :ref, :currency, :ledger, :stripe, :status, :note, :by, :at, :created)
                """)
                .param("id", i.id()).param("run", i.runId()).param("window", Sql.ts(i.windowStart()))
                .param("ref", i.stripeRef()).param("currency", i.currency()).param("ledger", i.ledgerMinor())
                .param("stripe", i.stripeMinor()).param("status", i.status().name()).param("note", i.note())
                .param("by", i.resolvedBy()).param("at", Sql.ts(i.resolvedAt())).param("created", Sql.ts(i.createdAt()))
                .update();
    }

    @Override
    public Optional<ReconciliationItem> find(UUID id) {
        return jdbc.sql("select * from reconciliation_item where id = :id").param("id", id)
                .query(JdbcReconciliationItemRepository::map).optional();
    }

    @Override
    public Optional<ReconciliationItem> lock(UUID id) {
        return jdbc.sql("select * from reconciliation_item where id = :id for update").param("id", id)
                .query(JdbcReconciliationItemRepository::map).optional();
    }

    @Override
    public ReconciliationItem update(ReconciliationItem i) {
        jdbc.sql("""
                update reconciliation_item set status = :status, note = :note, resolved_by = :by, resolved_at = :at
                where id = :id
                """)
                .param("status", i.status().name()).param("note", i.note()).param("by", i.resolvedBy())
                .param("at", Sql.ts(i.resolvedAt())).param("id", i.id()).update();
        return i;
    }

    @Override
    public List<ReconciliationItem> list(ReconciliationStatus status, int limit) {
        return jdbc.sql("""
                select * from reconciliation_item
                where (cast(:status as varchar) is null or status = :status)
                order by created_at desc, stripe_ref limit :limit
                """)
                .param("status", status == null ? null : status.name()).param("limit", limit)
                .query(JdbcReconciliationItemRepository::map).list();
    }

    @Override
    public List<ReconciliationItem> unresolvedOfWindow(Instant windowStart) {
        return jdbc.sql("""
                select * from reconciliation_item
                where window_start = :window and status in ('OPEN', 'EXPLAINED')
                """)
                .param("window", Sql.ts(windowStart)).query(JdbcReconciliationItemRepository::map).list();
    }

    @Override
    public List<ReconciliationItem> ofRun(UUID runId) {
        return jdbc.sql("select * from reconciliation_item where run_id = :run order by stripe_ref")
                .param("run", runId).query(JdbcReconciliationItemRepository::map).list();
    }

    private static ReconciliationItem map(ResultSet rs, int n) throws SQLException {
        return new ReconciliationItem(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                Sql.instant(rs, "window_start"), rs.getString("stripe_ref"), rs.getString("currency"),
                rs.getLong("ledger_minor"), rs.getLong("stripe_minor"),
                ReconciliationStatus.valueOf(rs.getString("status")), rs.getString("note"),
                rs.getString("resolved_by"), Sql.instant(rs, "resolved_at"), Sql.instant(rs, "created_at"));
    }
}
