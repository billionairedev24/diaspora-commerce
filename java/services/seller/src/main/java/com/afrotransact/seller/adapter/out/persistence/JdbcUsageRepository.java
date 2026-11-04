package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.UsageDay;
import com.afrotransact.seller.domain.UsageMeter;
import com.afrotransact.seller.port.UsageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUsageRepository implements UsageRepository {

    private static final String LEASE = "metering";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcUsageRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean upsert(String orgId, UsageMeter meter, LocalDate day, long quantity, Instant at) {
        return jdbc.sql("""
                insert into usage_daily (org_id, meter, day, quantity, updated_at)
                values (:org, :meter, :day, :quantity, :at)
                on conflict (org_id, meter, day) do update set quantity = excluded.quantity,
                    updated_at = excluded.updated_at
                where usage_daily.quantity <> excluded.quantity
                """)
                .param("org", orgId).param("meter", meter.name()).param("day", Date.valueOf(day))
                .param("quantity", quantity).param("at", Sql.ts(at))
                .update() == 1;
    }

    @Override
    public List<UsageDay> byMeter(UsageMeter meter, LocalDate from, LocalDate to) {
        return jdbc.sql("select * from usage_daily where meter = :meter and day between :from and :to")
                .param("meter", meter.name()).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query(JdbcUsageRepository::day).list();
    }

    @Override
    public List<UsageDay> byOrg(String orgId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select * from usage_daily where org_id = :org and day between :from and :to order by meter, day
                """)
                .param("org", orgId).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query(JdbcUsageRepository::day).list();
    }

    @Override
    public List<UsageDay> unreported(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select * from usage_daily where quantity <> reported_quantity and day between :from and :to
                order by org_id, meter, day
                """)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query(JdbcUsageRepository::day).list();
    }

    @Override
    public List<UsageDay> unbilled(String orgId, LocalDate through) {
        return jdbc.sql("""
                select * from usage_daily where org_id = :org and quantity <> reported_quantity and day <= :through
                order by meter, day
                """)
                .param("org", orgId).param("through", Date.valueOf(through))
                .query(JdbcUsageRepository::day).list();
    }

    @Override
    public Optional<UsageDay> lock(String orgId, UsageMeter meter, LocalDate day) {
        return jdbc.sql("select * from usage_daily where org_id = :org and meter = :meter and day = :day for update")
                .param("org", orgId).param("meter", meter.name()).param("day", Date.valueOf(day))
                .query(JdbcUsageRepository::day).optional();
    }

    @Override
    public void markReported(UsageDay d, long reportedQuantity, int revision, String identifier, long value,
                             Instant at) {
        jdbc.sql("""
                insert into usage_report (identifier, org_id, meter, day, value, created_at)
                values (:id, :org, :meter, :day, :value, :at)
                on conflict (identifier) do nothing
                """)
                .param("id", identifier).param("org", d.orgId()).param("meter", d.meter().name())
                .param("day", Date.valueOf(d.day())).param("value", value)
                .param("at", Sql.ts(at))
                .update();
        jdbc.sql("""
                update usage_daily set reported_quantity = :reported, report_revision = :revision, reported_at = :at,
                       last_error = null
                where org_id = :org and meter = :meter and day = :day
                """)
                .param("reported", reportedQuantity).param("revision", revision).param("at", Sql.ts(at))
                .param("org", d.orgId()).param("meter", d.meter().name()).param("day", Date.valueOf(d.day()))
                .update();
    }

    @Override
    public void markError(UsageDay d, String error, Instant at) {
        jdbc.sql("""
                update usage_daily set last_error = :error
                where org_id = :org and meter = :meter and day = :day
                  and last_error is distinct from cast(:error as text)
                """)
                .param("error", error).param("org", d.orgId()).param("meter", d.meter().name())
                .param("day", Date.valueOf(d.day()))
                .update();
    }

    @Override
    public Set<String> knownOrgs(Collection<String> orgIds) {
        if (orgIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select id from organisation where id in (:ids)")
                .param("ids", List.copyOf(orgIds)).query(String.class).list());
    }

    @Override
    public boolean tryLease(UUID holder, Instant now, Duration ttl) {
        return jdbc.sql("""
                update usage_run_lease set holder = :holder, leased_until = :until
                where name = :name and (holder is null or leased_until < :now)
                """)
                .param("holder", holder).param("until", Sql.ts(now.plus(ttl))).param("name", LEASE)
                .param("now", Sql.ts(now))
                .update() == 1;
    }

    @Override
    public void releaseLease(UUID holder) {
        jdbc.sql("update usage_run_lease set holder = null where name = :name and holder = :holder")
                .param("name", LEASE).param("holder", holder).update();
    }

    @Override
    public void saveRun(UsageRun r) {
        jdbc.sql("""
                insert into usage_run (id, from_day, to_day, trigger, actor_id, status, rows_changed, reported,
                                       pending, corrections, failed_sources, started_at, finished_at)
                values (:id, :from, :to, :trigger, :actor, :status, :rows, :reported, :pending, :corrections,
                        cast(:failed as jsonb), :started, :finished)
                on conflict (id) do update set status = excluded.status, rows_changed = excluded.rows_changed,
                    reported = excluded.reported, pending = excluded.pending, corrections = excluded.corrections,
                    failed_sources = excluded.failed_sources, finished_at = excluded.finished_at
                """)
                .param("id", r.id()).param("from", Date.valueOf(r.from())).param("to", Date.valueOf(r.to()))
                .param("trigger", r.trigger()).param("actor", r.actorId()).param("status", r.status())
                .param("rows", r.rowsChanged()).param("reported", r.reported()).param("pending", r.pending())
                .param("corrections", r.corrections()).param("failed", json.write(r.failedSources()))
                .param("started", Sql.ts(r.startedAt())).param("finished", Sql.ts(r.finishedAt()))
                .update();
    }

    @Override
    public List<UsageRun> runs(int limit) {
        return jdbc.sql("select * from usage_run order by started_at desc limit :limit").param("limit", limit)
                .query((rs, n) -> new UsageRun(rs.getObject("id", UUID.class), rs.getDate("from_day").toLocalDate(),
                        rs.getDate("to_day").toLocalDate(), rs.getString("trigger"), rs.getString("actor_id"),
                        rs.getString("status"), rs.getInt("rows_changed"), rs.getInt("reported"),
                        rs.getInt("pending"), rs.getInt("corrections"), json.strings(rs.getString("failed_sources")),
                        Sql.instant(rs, "started_at"), Sql.instant(rs, "finished_at")))
                .list();
    }

    private static UsageDay day(ResultSet rs, int n) throws SQLException {
        return new UsageDay(rs.getString("org_id"), UsageMeter.valueOf(rs.getString("meter")),
                rs.getDate("day").toLocalDate(), rs.getLong("quantity"), rs.getLong("reported_quantity"),
                rs.getInt("report_revision"), Sql.instant(rs, "reported_at"), rs.getString("last_error"),
                Sql.instant(rs, "updated_at"));
    }
}
