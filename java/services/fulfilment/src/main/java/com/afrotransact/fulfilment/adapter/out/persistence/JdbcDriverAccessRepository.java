package com.afrotransact.fulfilment.adapter.out.persistence;

import java.util.Optional;

import com.afrotransact.fulfilment.domain.AccessSource;
import com.afrotransact.fulfilment.domain.DriverAccess;
import com.afrotransact.fulfilment.domain.DriverAccessStatus;
import com.afrotransact.fulfilment.port.DriverAccessRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDriverAccessRepository implements DriverAccessRepository {

    private final JdbcClient jdbc;

    JdbcDriverAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DriverAccess> find(String driverId) {
        return jdbc.sql("select * from driver_access where driver_id = :id").param("id", driverId)
                .query((rs, n) -> new DriverAccess(rs.getString("driver_id"),
                        DriverAccessStatus.valueOf(rs.getString("status")), Rows.instant(rs, "changed_at"),
                        rs.getString("reason"), AccessSource.valueOf(rs.getString("source"))))
                .optional();
    }

    @Override
    public boolean apply(DriverAccess a) {
        return jdbc.sql("""
                insert into driver_access (driver_id, status, changed_at, reason, source, updated_at)
                values (:id, :status, :at, :reason, :source, now())
                on conflict (driver_id) do update set status = excluded.status, changed_at = excluded.changed_at,
                    reason = excluded.reason, source = excluded.source, updated_at = now()
                where driver_access.changed_at <= excluded.changed_at
                """)
                .param("id", a.driverId()).param("status", a.status().name()).param("at", Rows.ts(a.changedAt()))
                .param("reason", a.reason() == null || a.reason().length() <= 300 ? a.reason()
                        : a.reason().substring(0, 300))
                .param("source", a.source().name())
                .update() == 1;
    }
}
