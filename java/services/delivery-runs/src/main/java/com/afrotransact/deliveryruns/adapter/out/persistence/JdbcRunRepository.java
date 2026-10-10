package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DeliveryWindow;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunMilestones;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.RunRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunRepository implements RunRepository {

    private static final String ACCEPTING = "status in ('OPEN', 'EXTENDED')";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcRunRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Run insert(Run r) {
        bind(jdbc.sql("""
                insert into run (id, location_id, status, window_start, window_end, cutoff_at, original_cutoff_at,
                    opens_at, seller_pickup_at, fee_minor, currency, min_orders, capacity_orders, capacity_packages,
                    drop_mode, pickup_point, cutoff_policy, extend_days, extensions, reserved_orders,
                    reserved_packages, cancel_reason, reminder_sent_for, est_road_miles, est_drive_minutes,
                    est_method, announced_at, opened_at, cut_off_at, confirmed_at, collecting_at,
                    out_for_delivery_at, completed_at, cancelled_at, created_at, updated_at, version)
                values (:id, :locationId, :status, :windowStart, :windowEnd, :cutoffAt, :originalCutoffAt,
                    :opensAt, :sellerPickupAt, :fee, :currency, :minOrders, :capOrders, :capPackages,
                    :dropMode, cast(:pickup as jsonb), :policy, :extendDays, :extensions, :reservedOrders,
                    :reservedPackages, :cancelReason, :reminderSentFor, :estMiles, :estMinutes,
                    :estMethod, :announcedAt, :openedAt, :cutOffAt, :confirmedAt, :collectingAt,
                    :outForDeliveryAt, :completedAt, :cancelledAt, :createdAt, :updatedAt, 0)
                """), r).param("locationId", r.locationId()).param("createdAt", Sql.ts(r.createdAt())).update();
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Run update(Run r) {
        int rows = bind(jdbc.sql("""
                update run set status = :status, window_start = :windowStart, window_end = :windowEnd,
                    cutoff_at = :cutoffAt, original_cutoff_at = :originalCutoffAt, opens_at = :opensAt,
                    seller_pickup_at = :sellerPickupAt, fee_minor = :fee, currency = :currency,
                    min_orders = :minOrders, capacity_orders = :capOrders, capacity_packages = :capPackages,
                    drop_mode = :dropMode, pickup_point = cast(:pickup as jsonb), cutoff_policy = :policy,
                    extend_days = :extendDays, extensions = :extensions, reserved_orders = :reservedOrders,
                    reserved_packages = :reservedPackages, cancel_reason = :cancelReason,
                    reminder_sent_for = :reminderSentFor, est_road_miles = :estMiles,
                    est_drive_minutes = :estMinutes, est_method = :estMethod, announced_at = :announcedAt,
                    opened_at = :openedAt, cut_off_at = :cutOffAt, confirmed_at = :confirmedAt,
                    collecting_at = :collectingAt, out_for_delivery_at = :outForDeliveryAt,
                    completed_at = :completedAt, cancelled_at = :cancelledAt, updated_at = :updatedAt,
                    version = version + 1
                where id = :id and version = :version
                """), r).param("version", r.version()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Run changed; reload and retry");
        }
        return findById(r.id()).orElseThrow();
    }

    @Override
    public Optional<Run> findById(UUID id) {
        return jdbc.sql("select * from run where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Run> lockById(UUID id) {
        return jdbc.sql("select * from run where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Run> tryLock(UUID id) {
        return jdbc.sql("select * from run where id = :id for update skip locked").param("id", id)
                .query(this::map).optional();
    }

    @Override
    public List<Run> find(UUID locationId, Set<RunStatus> statuses, int limit) {
        StringBuilder sql = new StringBuilder("select * from run where true");
        if (locationId != null) {
            sql.append(" and location_id = :locationId");
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and status in (:statuses)");
        }
        sql.append(" order by window_start desc limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (locationId != null) {
            spec = spec.param("locationId", locationId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.param("statuses", statuses.stream().map(Enum::name).toList());
        }
        return spec.query(this::map).list();
    }

    @Override
    public List<Run> findAccepting(Collection<UUID> locationIds, Instant now) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from run where location_id in (:ids) and " + ACCEPTING
                        + " and cutoff_at > :now order by cutoff_at")
                .param("ids", List.copyOf(locationIds)).param("now", Sql.ts(now)).query(this::map).list();
    }

    @Override
    public List<Run> lockAcceptingAfter(UUID locationId, Instant afterCutoff, Instant now, int limit) {
        return jdbc.sql("select * from run where location_id = :locationId and " + ACCEPTING
                        + " and cutoff_at > :after and cutoff_at > :now order by cutoff_at limit :limit for update")
                .param("locationId", locationId).param("after", Sql.ts(afterCutoff)).param("now", Sql.ts(now))
                .param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Run> findUpcoming(UUID locationId, Instant now) {
        return jdbc.sql("""
                select * from run where location_id = :locationId
                    and status in ('ANNOUNCED', 'OPEN', 'EXTENDED', 'CUT_OFF', 'CONFIRMED', 'COLLECTING',
                                   'OUT_FOR_DELIVERY')
                    and window_end > :now
                order by window_start
                """).param("locationId", locationId).param("now", Sql.ts(now)).query(this::map).list();
    }

    @Override
    public List<UUID> dueForCutoff(Instant now, int limit) {
        return jdbc.sql("select id from run where " + ACCEPTING + " and cutoff_at <= :now order by cutoff_at limit :l")
                .param("now", Sql.ts(now)).param("l", limit).query(UUID.class).list();
    }

    @Override
    public List<UUID> dueForReminder(Instant now, Duration lead, int limit) {
        return jdbc.sql("select id from run where " + ACCEPTING + " and cutoff_at > :now and cutoff_at <= :horizon"
                        + " and (reminder_sent_for is null or reminder_sent_for <> cutoff_at)"
                        + " order by cutoff_at limit :l")
                .param("now", Sql.ts(now)).param("horizon", Sql.ts(now.plus(lead))).param("l", limit)
                .query(UUID.class).list();
    }

    @Override
    public List<UUID> dueToOpen(Instant now, int limit) {
        return jdbc.sql("select id from run where status = 'ANNOUNCED' and opens_at is not null and opens_at <= :now"
                        + " and cutoff_at > :now order by opens_at limit :l")
                .param("now", Sql.ts(now)).param("l", limit).query(UUID.class).list();
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("delete from run where id = :id and status = 'DRAFT'").param("id", id).update();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Run r) {
        RunTerms t = r.terms();
        RunMilestones m = r.milestones();
        RouteEstimate e = r.estimate();
        return spec.param("id", r.id())
                .param("status", r.status().name())
                .param("windowStart", Sql.ts(t.window().start()))
                .param("windowEnd", Sql.ts(t.window().end()))
                .param("cutoffAt", Sql.ts(t.cutoffAt()))
                .param("originalCutoffAt", Sql.ts(r.originalCutoffAt()))
                .param("opensAt", Sql.ts(t.opensAt()))
                .param("sellerPickupAt", Sql.ts(t.sellerPickupAt()))
                .param("fee", t.feeMinor())
                .param("currency", t.currency())
                .param("minOrders", t.minOrders())
                .param("capOrders", t.capacity().maxOrders())
                .param("capPackages", t.capacity().maxPackages())
                .param("dropMode", t.dropMode().name())
                .param("pickup", json.write(t.pickupPoint()))
                .param("policy", t.cutoffPolicy().name())
                .param("extendDays", t.extendDays())
                .param("extensions", r.extensions())
                .param("reservedOrders", r.reservedOrders())
                .param("reservedPackages", r.reservedPackages())
                .param("cancelReason", r.cancelReason())
                .param("reminderSentFor", Sql.ts(r.reminderSentFor()))
                .param("estMiles", e == null ? null : e.roadMiles())
                .param("estMinutes", e == null ? null : e.driveMinutes())
                .param("estMethod", e == null ? null : e.method())
                .param("announcedAt", Sql.ts(m.announcedAt()))
                .param("openedAt", Sql.ts(m.openedAt()))
                .param("cutOffAt", Sql.ts(m.cutOffAt()))
                .param("confirmedAt", Sql.ts(m.confirmedAt()))
                .param("collectingAt", Sql.ts(m.collectingAt()))
                .param("outForDeliveryAt", Sql.ts(m.outForDeliveryAt()))
                .param("completedAt", Sql.ts(m.completedAt()))
                .param("cancelledAt", Sql.ts(m.cancelledAt()))
                .param("updatedAt", Sql.ts(r.updatedAt()));
    }

    private Run map(ResultSet rs, int rowNum) throws SQLException {
        RunTerms terms = new RunTerms(
                new DeliveryWindow(Sql.instant(rs, "window_start"), Sql.instant(rs, "window_end")),
                Sql.instant(rs, "cutoff_at"), Sql.instant(rs, "opens_at"), Sql.instant(rs, "seller_pickup_at"),
                rs.getLong("fee_minor"), rs.getString("currency"), rs.getInt("min_orders"),
                new Capacity(Sql.integer(rs, "capacity_orders"), Sql.integer(rs, "capacity_packages")),
                DropMode.valueOf(rs.getString("drop_mode")), json.pickupPoint(rs.getString("pickup_point")),
                CutoffPolicy.valueOf(rs.getString("cutoff_policy")), rs.getInt("extend_days"));
        Double miles = Sql.dbl(rs, "est_road_miles");
        Double minutes = Sql.dbl(rs, "est_drive_minutes");
        RouteEstimate estimate = miles == null || minutes == null ? null
                : new RouteEstimate(miles, minutes, rs.getString("est_method"));
        RunMilestones milestones = new RunMilestones(Sql.instant(rs, "announced_at"), Sql.instant(rs, "opened_at"),
                Sql.instant(rs, "cut_off_at"), Sql.instant(rs, "confirmed_at"), Sql.instant(rs, "collecting_at"),
                Sql.instant(rs, "out_for_delivery_at"), Sql.instant(rs, "completed_at"),
                Sql.instant(rs, "cancelled_at"));
        return new Run(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                RunStatus.valueOf(rs.getString("status")), terms, Sql.instant(rs, "original_cutoff_at"),
                rs.getInt("extensions"), rs.getInt("reserved_orders"), rs.getInt("reserved_packages"),
                rs.getString("cancel_reason"), Sql.instant(rs, "reminder_sent_for"), estimate, milestones,
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
