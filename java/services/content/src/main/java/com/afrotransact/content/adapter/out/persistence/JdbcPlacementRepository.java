package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.placement.FrequencyCap;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Schedule;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.domain.placement.Sponsorship;
import com.afrotransact.content.domain.placement.Targeting;
import com.afrotransact.content.port.PlacementRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPlacementRepository implements PlacementRepository {

    private static final String SELECT = """
            select id, slot, name, status, priority, payload::text as payload, targeting::text as targeting,
                   starts_at, ends_at, frequency_cap::text as frequency_cap, paid, advertiser, created_by,
                   submitted_by, approved_by, review_note, version, created_at, updated_at
            from placement
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcPlacementRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Placement> find(PlacementStatus status, SlotKey slot, int limit) {
        return jdbc.sql(SELECT + """
                 where (cast(:status as varchar) is null or status = :status)
                   and (cast(:slot as varchar) is null or slot = :slot)
                 order by updated_at desc, id limit :limit
                """)
                .param("status", status == null ? null : status.name())
                .param("slot", slot == null ? null : slot.key())
                .param("limit", limit)
                .query(this::map).list();
    }

    @Override
    public List<Placement> findByStatus(Collection<PlacementStatus> statuses) {
        return jdbc.sql(SELECT + " where status in (:statuses) order by id")
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .query(this::map).list();
    }

    @Override
    public Optional<Placement> findById(String id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public void insert(Placement p) {
        jdbc.sql("""
                insert into placement (id, slot, name, status, priority, payload, targeting, starts_at, ends_at,
                    frequency_cap, paid, advertiser, created_by, submitted_by, approved_by, review_note, version,
                    created_at, updated_at)
                values (:id, :slot, :name, :status, :priority, cast(:payload as jsonb), cast(:targeting as jsonb),
                    :startsAt, :endsAt, cast(:cap as jsonb), :paid, :advertiser, :createdBy, :submittedBy,
                    :approvedBy, :reviewNote, 0, :createdAt, :updatedAt)
                """)
                .param("id", p.id())
                .param("slot", p.slot().key())
                .param("createdBy", p.createdBy())
                .param("createdAt", Timestamps.of(p.createdAt()))
                .params(columns(p))
                .update();
    }

    @Override
    public boolean deleteById(String id) {
        return jdbc.sql("delete from placement where id = :id").param("id", id).update() == 1;
    }

    @Override
    public boolean update(Placement p) {
        return jdbc.sql("""
                update placement set name = :name, status = :status, priority = :priority,
                    payload = cast(:payload as jsonb), targeting = cast(:targeting as jsonb), starts_at = :startsAt,
                    ends_at = :endsAt, frequency_cap = cast(:cap as jsonb), paid = :paid, advertiser = :advertiser,
                    submitted_by = :submittedBy, approved_by = :approvedBy, review_note = :reviewNote,
                    version = version + 1, updated_at = :updatedAt
                where id = :id and version = :version
                """)
                .param("id", p.id())
                .param("version", p.version())
                .params(columns(p))
                .update() == 1;
    }

    private Map<String, Object> columns(Placement p) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", p.name());
        m.put("status", p.status().name());
        m.put("priority", p.priority());
        m.put("payload", p.payload());
        m.put("targeting", json.write(p.targeting()));
        m.put("startsAt", Timestamps.of(p.schedule().startsAt()));
        m.put("endsAt", Timestamps.of(p.schedule().endsAt()));
        m.put("cap", json.write(p.frequencyCap()));
        m.put("paid", p.sponsorship().paid());
        m.put("advertiser", p.sponsorship().advertiser());
        m.put("submittedBy", p.submittedBy());
        m.put("approvedBy", p.approvedBy());
        m.put("reviewNote", p.reviewNote());
        m.put("updatedAt", Timestamps.of(p.updatedAt()));
        return m;
    }

    private Placement map(ResultSet rs, int row) throws SQLException {
        return new Placement(rs.getString("id"), SlotKey.fromKey(rs.getString("slot")), rs.getString("name"),
                rs.getString("payload"), json.read(rs.getString("targeting"), Targeting.class),
                new Schedule(Timestamps.instant(rs, "starts_at"), Timestamps.instant(rs, "ends_at")),
                json.read(rs.getString("frequency_cap"), FrequencyCap.class), rs.getInt("priority"),
                new Sponsorship(rs.getBoolean("paid"), rs.getString("advertiser")),
                PlacementStatus.valueOf(rs.getString("status")), rs.getString("created_by"),
                rs.getString("submitted_by"), rs.getString("approved_by"), rs.getString("review_note"),
                rs.getLong("version"), Timestamps.instant(rs, "created_at"), Timestamps.instant(rs, "updated_at"));
    }
}
