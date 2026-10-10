package com.afrotransact.inventory.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.PositionDrift;
import com.afrotransact.inventory.domain.ReconciliationReport;
import com.afrotransact.inventory.port.ReconciliationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcReconciliationRepository implements ReconciliationRepository {

    private static final TypeReference<List<PositionDrift>> DRIFTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcReconciliationRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(ReconciliationReport r) {
        jdbc.sql("""
                insert into reconciliation_run (id, ran_at, positions_checked, drift_units, drifts)
                values (:id, :at, :checked, :drift, cast(:drifts as jsonb))
                """)
                .param("id", r.id()).param("at", Timestamp.from(r.ranAt())).param("checked", r.positionsChecked())
                .param("drift", r.driftUnits()).param("drifts", json.write(r.drifts()))
                .update();
    }

    @Override
    public Optional<ReconciliationReport> latest() {
        return jdbc.sql("""
                select id, ran_at, positions_checked, drift_units, drifts::text as drifts
                from reconciliation_run order by ran_at desc limit 1
                """)
                .query((rs, n) -> new ReconciliationReport(rs.getObject("id", UUID.class),
                        rs.getTimestamp("ran_at").toInstant(), rs.getInt("positions_checked"),
                        rs.getLong("drift_units"), json.read(rs.getString("drifts"), DRIFTS)))
                .optional();
    }
}
