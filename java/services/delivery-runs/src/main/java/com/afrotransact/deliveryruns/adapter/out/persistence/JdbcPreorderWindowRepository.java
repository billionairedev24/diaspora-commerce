package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeRef;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.Money;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.deliveryruns.domain.WindowOutcome;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcPreorderWindowRepository implements PreorderWindowRepository {

    private static final TypeReference<Set<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Set<CommKind>> COMMS = new TypeReference<>() { };

    /** A window row before its targets and creatives are attached. */
    private record Row(UUID id, String code, String name, Instant opensAt, Instant closesAt,
                       LocalDate deliversOn, ZoneId zone, String ribbon, String headline, String sms,
                       Set<String> categories, boolean door, boolean pickup, String pickupPointId,
                       String pickupAddress, boolean showCount, long budgetMinor, String currency,
                       Set<CommKind> comms, Long doorFeeMinor, Long pickupFeeMinor, UUID locationId, int minOrders,
                       boolean draft, boolean active, UUID runId, WindowOutcome outcome, Instant createdAt,
                       Instant updatedAt, long version) {
    }

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPreorderWindowRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public PreorderWindow insert(PreorderWindow w) {
        try {
            bind(jdbc.sql("""
                    insert into preorder_window (id, code, name, opens_at, closes_at, delivers_on, time_zone, ribbon,
                        headline, sms_copy, categories, door, pickup, pickup_point_id, pickup_address, show_count,
                        social_budget_minor, currency, comms, door_fee_override_minor, pickup_fee_override_minor,
                        location_id, min_orders, draft, active, run_id, outcome, created_at, updated_at, version)
                    values (:id, :code, :name, :opensAt, :closesAt, :deliversOn, :zone, :ribbon, :headline, :sms,
                        cast(:categories as jsonb), :door, :pickup, :pickupPointId, :pickupAddress, :showCount,
                        :budget, :currency, cast(:comms as jsonb), :doorFee, :pickupFee, :locationId, :minOrders, :draft,
                        :active, :runId, :outcome, :createdAt, :updatedAt, 0)
                    """), w).param("code", w.code()).param("createdAt", Sql.ts(w.createdAt())).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("Pre-order code " + w.code() + " is taken", e);
        }
        writeChildren(w);
        return findById(w.id()).orElseThrow();
    }

    @Override
    public PreorderWindow update(PreorderWindow w) {
        int rows = bind(jdbc.sql("""
                update preorder_window set name = :name, opens_at = :opensAt, closes_at = :closesAt,
                    delivers_on = :deliversOn, time_zone = :zone, ribbon = :ribbon, headline = :headline,
                    sms_copy = :sms, categories = cast(:categories as jsonb), door = :door, pickup = :pickup,
                    pickup_point_id = :pickupPointId, pickup_address = :pickupAddress, show_count = :showCount,
                    social_budget_minor = :budget, currency = :currency, comms = cast(:comms as jsonb),
                    door_fee_override_minor = :doorFee, pickup_fee_override_minor = :pickupFee,
                    location_id = :locationId, min_orders = :minOrders, draft = :draft, active = :active,
                    run_id = :runId, outcome = :outcome, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """), w).param("version", w.version()).update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Pre-order changed; reload and retry");
        }
        jdbc.sql("delete from preorder_window_target where window_id = :id").param("id", w.id()).update();
        jdbc.sql("delete from preorder_creative where window_id = :id").param("id", w.id()).update();
        writeChildren(w);
        return findById(w.id()).orElseThrow();
    }

    @Override
    public Optional<PreorderWindow> findById(UUID id) {
        return one("select * from preorder_window where id = :id", id);
    }

    @Override
    public Optional<PreorderWindow> lockById(UUID id) {
        return one("select * from preorder_window where id = :id for update", id);
    }

    @Override
    public Optional<PreorderWindow> tryLock(UUID id) {
        return one("select * from preorder_window where id = :id for update skip locked", id);
    }

    @Override
    public List<PreorderWindow> findAll() {
        return assemble(jdbc.sql("select * from preorder_window order by opens_at desc, code")
                .query(this::row).list());
    }

    @Override
    public List<PreorderWindow> findPublished() {
        return assemble(jdbc.sql("select * from preorder_window where draft = false order by opens_at desc, code")
                .query(this::row).list());
    }

    private Optional<PreorderWindow> one(String sql, UUID id) {
        List<Row> rows = jdbc.sql(sql).param("id", id).query(this::row).list();
        return assemble(rows).stream().findFirst();
    }

    private List<PreorderWindow> assemble(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Row::id).toList();
        Map<UUID, List<AreaKey>> targets = new HashMap<>();
        jdbc.sql("select * from preorder_window_target where window_id in (:ids) order by window_id, position")
                .param("ids", ids)
                .query((rs, n) -> {
                    targets.computeIfAbsent(rs.getObject("window_id", UUID.class), k -> new ArrayList<>())
                            .add(new AreaKey(rs.getString("country"), rs.getString("region"), rs.getString("city")));
                    return null;
                }).list();
        Map<UUID, Map<CreativeSlot, CreativeRef>> creatives = new HashMap<>();
        jdbc.sql("select * from preorder_creative where window_id in (:ids)").param("ids", ids)
                .query((rs, n) -> {
                    creatives.computeIfAbsent(rs.getObject("window_id", UUID.class),
                            k -> new EnumMap<>(CreativeSlot.class))
                            .put(CreativeSlot.valueOf(rs.getString("slot")), new CreativeRef(
                                    rs.getObject("asset_id", UUID.class), rs.getString("url"), rs.getInt("width"),
                                    rs.getInt("height")));
                    return null;
                }).list();
        List<PreorderWindow> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            WindowDetails d = new WindowDetails(r.name(), targets.getOrDefault(r.id(), List.of()), r.opensAt(),
                    r.closesAt(), r.deliversOn(), r.zone(), r.ribbon(), r.headline(), r.sms(), r.categories(),
                    r.door(), r.pickup(), r.pickupPointId(), r.pickupAddress(), r.showCount(),
                    new Money(r.budgetMinor(), r.currency()), r.comms(),
                    r.doorFeeMinor() == null ? null : new Money(r.doorFeeMinor(), r.currency()),
                    r.pickupFeeMinor() == null ? null : new Money(r.pickupFeeMinor(), r.currency()), r.locationId(),
                    r.minOrders());
            out.add(new PreorderWindow(r.id(), r.code(), d, creatives.getOrDefault(r.id(), Map.of()), r.draft(),
                    r.active(), r.runId(), r.outcome(), r.createdAt(), r.updatedAt(), r.version()));
        }
        return out;
    }

    private void writeChildren(PreorderWindow w) {
        List<AreaKey> targets = w.details().targets();
        for (int i = 0; i < targets.size(); i++) {
            AreaKey k = targets.get(i);
            jdbc.sql("""
                    insert into preorder_window_target (window_id, position, country, region, city)
                    values (:id, :position, :country, :region, :city)
                    """).param("id", w.id()).param("position", i).param("country", k.country())
                    .param("region", k.region()).param("city", k.city()).update();
        }
        w.creatives().forEach((slot, c) -> jdbc.sql("""
                insert into preorder_creative (window_id, slot, asset_id, url, width, height, updated_at)
                values (:id, :slot, :assetId, :url, :width, :height, :updatedAt)
                """).param("id", w.id()).param("slot", slot.name()).param("assetId", c.assetId())
                .param("url", c.url()).param("width", c.width()).param("height", c.height())
                .param("updatedAt", Sql.ts(w.updatedAt())).update());
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, PreorderWindow w) {
        WindowDetails d = w.details();
        String currency = d.socialBudget().currency();
        return spec.param("id", w.id())
                .param("name", d.name())
                .param("opensAt", Sql.ts(d.opensAt()))
                .param("closesAt", Sql.ts(d.closesAt()))
                .param("deliversOn", d.deliversOn())
                .param("zone", d.timeZone().getId())
                .param("ribbon", d.ribbon())
                .param("headline", d.headline())
                .param("sms", d.smsCopy())
                .param("categories", json.write(d.categories().stream().sorted().toList()))
                .param("door", d.door())
                .param("pickup", d.pickup())
                .param("pickupPointId", d.pickupPointId())
                .param("pickupAddress", d.pickupAddress())
                .param("showCount", d.showCount())
                .param("budget", d.socialBudget().minor())
                .param("currency", currency)
                .param("comms", json.write(d.comms().stream().sorted().map(Enum::name).toList()))
                .param("doorFee", d.doorFeeOverride() == null ? null : d.doorFeeOverride().minor())
                .param("pickupFee", d.pickupFeeOverride() == null ? null : d.pickupFeeOverride().minor())
                .param("locationId", d.locationId())
                .param("minOrders", d.minOrders())
                .param("draft", w.draft())
                .param("active", w.active())
                .param("runId", w.runId())
                .param("outcome", w.outcome() == null ? null : w.outcome().name())
                .param("updatedAt", Sql.ts(w.updatedAt()));
    }

    private Row row(ResultSet rs, int rowNum) throws SQLException {
        long door = rs.getLong("door_fee_override_minor");
        Long doorFee = rs.wasNull() ? null : door;
        long pickup = rs.getLong("pickup_fee_override_minor");
        Long pickupFee = rs.wasNull() ? null : pickup;
        return new Row(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                Sql.instant(rs, "opens_at"), Sql.instant(rs, "closes_at"),
                rs.getObject("delivers_on", LocalDate.class), ZoneId.of(rs.getString("time_zone")),
                rs.getString("ribbon"), rs.getString("headline"), rs.getString("sms_copy"),
                json.read(rs.getString("categories"), STRINGS), rs.getBoolean("door"), rs.getBoolean("pickup"),
                rs.getString("pickup_point_id"), rs.getString("pickup_address"), rs.getBoolean("show_count"),
                rs.getLong("social_budget_minor"), rs.getString("currency"), json.read(rs.getString("comms"), COMMS),
                doorFee, pickupFee, rs.getObject("location_id", UUID.class), rs.getInt("min_orders"),
                rs.getBoolean("draft"), rs.getBoolean("active"), rs.getObject("run_id", UUID.class),
                rs.getString("outcome") == null ? null : WindowOutcome.valueOf(rs.getString("outcome")),
                Sql.instant(rs, "created_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
