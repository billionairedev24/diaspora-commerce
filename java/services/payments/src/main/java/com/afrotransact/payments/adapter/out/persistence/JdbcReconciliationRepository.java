package com.afrotransact.payments.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.ReconciliationDrift;
import com.afrotransact.payments.domain.ReconciliationReport;
import com.afrotransact.payments.port.ReconciliationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcReconciliationRepository implements ReconciliationRepository {

    private static final TypeReference<List<DriftRow>> DRIFTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcReconciliationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(ReconciliationReport r) {
        jdbc.sql("""
                insert into reconciliation_run (id, window_start, window_end, currency, ledger_net_minor,
                    stripe_net_minor, drift_minor, drifts, created_at)
                values (:id, :from, :to, :currency, :ledger, :stripe, :drift, cast(:drifts as jsonb), :at)
                """)
                .param("id", r.id()).param("from", Sql.ts(r.windowStart())).param("to", Sql.ts(r.windowEnd()))
                .param("currency", r.currency()).param("ledger", r.ledgerNetMinor())
                .param("stripe", r.stripeNetMinor()).param("drift", r.driftMinor())
                .param("drifts", json.write(r.drifts().stream()
                        .map(d -> new DriftRow(d.stripeRef(), d.ledgerMinor(), d.stripeMinor())).toList()))
                .param("at", Sql.ts(r.createdAt()))
                .update();
    }

    @Override
    public List<ReconciliationReport> recent(int limit) {
        return jdbc.sql("select *, drifts::text as drifts_json from reconciliation_run order by created_at desc "
                        + "limit :limit")
                .param("limit", limit)
                .query((rs, n) -> new ReconciliationReport(rs.getObject("id", UUID.class),
                        Sql.instant(rs, "window_start"), Sql.instant(rs, "window_end"), rs.getString("currency"),
                        rs.getLong("ledger_net_minor"), rs.getLong("stripe_net_minor"), rs.getLong("drift_minor"),
                        json.read(rs.getString("drifts_json"), DRIFTS).stream()
                                .map(d -> new ReconciliationDrift(d.stripeRef(), d.ledgerMinor(), d.stripeMinor()))
                                .toList(), Sql.instant(rs, "created_at")))
                .list();
    }

    /** Stored drift. */
    record DriftRow(String stripeRef, long ledgerMinor, long stripeMinor) {
    }
}
