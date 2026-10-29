package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialCopy;
import com.afrotransact.deliveryruns.domain.AdvertorialEnd;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.AdvertorialStatus;
import com.afrotransact.deliveryruns.domain.FeaturedProduct;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import com.afrotransact.deliveryruns.port.AdvertorialRepository;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcAdvertorialRepository implements AdvertorialRepository {

    private static final TypeReference<List<FeaturedProduct>> FEATURED = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcAdvertorialRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public LocalAdvertorial insert(LocalAdvertorial a) {
        try {
            jdbc.sql("""
                    insert into local_advertorial (id, source_kind, source_id, location_id, variant, weight, priority,
                        generated, headline, body, cta_label, featured_products, starts_at, ends_at, status, ended,
                        created_by, created_at, updated_at, version)
                    values (:id, :kind, :sourceId, :location, :variant, :weight, :priority, :generated, :headline,
                        :body, :cta, cast(:featured as jsonb), :startsAt, :endsAt, :status, :ended, :createdBy,
                        :createdAt, :updatedAt, 0)
                    """).param("id", a.id()).param("kind", a.source().name()).param("sourceId", a.sourceId())
                    .param("location", a.locationId()).param("variant", a.variant()).param("weight", a.weight())
                    .param("priority", a.priority()).param("generated", a.generated())
                    .param("headline", a.copy().headline()).param("body", a.copy().body())
                    .param("cta", a.copy().ctaLabel()).param("featured", json.write(a.featured()))
                    .param("startsAt", Sql.ts(a.startsAt()))
                    .param("endsAt", Sql.ts(a.endsAt())).param("status", a.status().name())
                    .param("ended", a.ended() == null ? null : a.ended().name()).param("createdBy", a.createdBy())
                    .param("createdAt", Sql.ts(a.createdAt())).param("updatedAt", Sql.ts(a.updatedAt())).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("This " + a.source() + " already has a variant " + a.variant(), e);
        }
        return findById(a.id()).orElseThrow();
    }

    @Override
    public LocalAdvertorial update(LocalAdvertorial a) {
        int rows;
        try {
            rows = jdbc.sql("""
                    update local_advertorial set variant = :variant, weight = :weight, priority = :priority,
                        generated = :generated, headline = :headline, body = :body, cta_label = :cta,
                        featured_products = cast(:featured as jsonb), starts_at = :startsAt, ends_at = :endsAt,
                        status = :status, ended = :ended, updated_at = :updatedAt, version = version + 1
                    where id = :id and version = :version
                    """).param("id", a.id()).param("variant", a.variant()).param("weight", a.weight())
                    .param("priority", a.priority()).param("generated", a.generated())
                    .param("headline", a.copy().headline()).param("body", a.copy().body())
                    .param("cta", a.copy().ctaLabel()).param("featured", json.write(a.featured()))
                    .param("startsAt", Sql.ts(a.startsAt())).param("endsAt", Sql.ts(a.endsAt()))
                    .param("status", a.status().name()).param("ended", a.ended() == null ? null : a.ended().name())
                    .param("updatedAt", Sql.ts(a.updatedAt())).param("version", a.version()).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException("This " + a.source() + " already has a variant " + a.variant(), e);
        }
        if (rows == 0) {
            throw new ConcurrentUpdateException("The advertorial changed; reload and retry");
        }
        return findById(a.id()).orElseThrow();
    }

    @Override
    public Optional<LocalAdvertorial> findById(UUID id) {
        return jdbc.sql("select * from local_advertorial where id = :id").param("id", id).query(this::row).optional();
    }

    @Override
    public List<LocalAdvertorial> findBySource(AdvertorialSource source, UUID sourceId) {
        return jdbc.sql("select * from local_advertorial where source_kind = :kind and source_id = :id "
                + "order by variant").param("kind", source.name()).param("id", sourceId).query(this::row).list();
    }

    @Override
    public List<LocalAdvertorial> find(AdvertorialSource source, UUID sourceId, AdvertorialStatus status, int limit) {
        return jdbc.sql("""
                select * from local_advertorial
                where (cast(:kind as varchar) is null or source_kind = cast(:kind as varchar))
                  and (cast(:sourceId as uuid) is null or source_id = cast(:sourceId as uuid))
                  and (cast(:status as varchar) is null or status = cast(:status as varchar))
                order by created_at desc, variant limit :limit
                """).param("kind", source == null ? null : source.name()).param("sourceId", sourceId)
                .param("status", status == null ? null : status.name()).param("limit", limit)
                .query(this::row).list();
    }

    @Override
    public List<LocalAdvertorial> findLive(Collection<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from local_advertorial where status = 'LIVE' and source_id in (:ids) "
                + "order by priority desc, variant").param("ids", sourceIds).query(this::row).list();
    }

    @Override
    public void count(UUID advertorialId, LocalDate day, long views, long clicks) {
        jdbc.sql("""
                insert into local_advertorial_stat (advertorial_id, day, views, clicks)
                values (:id, :day, :views, :clicks)
                on conflict (advertorial_id, day) do update
                    set views = local_advertorial_stat.views + excluded.views,
                        clicks = local_advertorial_stat.clicks + excluded.clicks
                """).param("id", advertorialId).param("day", day).param("views", views).param("clicks", clicks)
                .update();
    }

    @Override
    public boolean admit(UUID advertorialId, String subject, String kind, Instant now, Duration window, int max) {
        // One statement: a new subject is admitted; a known one only when its window ended (a new window starts) or
        // it has places left in the current one.
        return jdbc.sql("""
                insert into local_advertorial_gate (advertorial_id, subject, kind, window_start, hits)
                values (:id, :subject, :kind, :now, 1)
                on conflict (advertorial_id, subject, kind) do update
                    set window_start = case when local_advertorial_gate.window_start <= :expired
                            then excluded.window_start else local_advertorial_gate.window_start end,
                        hits = case when local_advertorial_gate.window_start <= :expired
                            then 1 else local_advertorial_gate.hits + 1 end
                    where local_advertorial_gate.window_start <= :expired or local_advertorial_gate.hits < :max
                """).param("id", advertorialId).param("subject", subject).param("kind", kind)
                .param("now", Sql.ts(now)).param("expired", Sql.ts(now.minus(window))).param("max", max)
                .update() > 0;
    }

    @Override
    public int purgeGates(Instant before) {
        return jdbc.sql("delete from local_advertorial_gate where window_start < :before")
                .param("before", Sql.ts(before)).update();
    }

    @Override
    public Map<UUID, Counters> counters(Collection<UUID> advertorialIds) {
        if (advertorialIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Counters> out = new HashMap<>();
        jdbc.sql("""
                select advertorial_id, sum(views) as views, sum(clicks) as clicks from local_advertorial_stat
                where advertorial_id in (:ids) group by advertorial_id
                """).param("ids", advertorialIds).query((rs, n) -> {
                    out.put(rs.getObject("advertorial_id", UUID.class),
                            new Counters(rs.getLong("views"), rs.getLong("clicks")));
                    return 0;
                }).list();
        return out;
    }

    private LocalAdvertorial row(ResultSet rs, int n) throws SQLException {
        String ended = rs.getString("ended");
        return new LocalAdvertorial(rs.getObject("id", UUID.class),
                AdvertorialSource.valueOf(rs.getString("source_kind")), rs.getObject("source_id", UUID.class),
                rs.getObject("location_id", UUID.class), rs.getString("variant"), rs.getInt("weight"),
                rs.getInt("priority"), rs.getBoolean("generated"),
                new AdvertorialCopy(rs.getString("headline"), rs.getString("body"), rs.getString("cta_label")),
                Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"),
                AdvertorialStatus.valueOf(rs.getString("status")), ended == null ? null : AdvertorialEnd.valueOf(ended),
                json.read(rs.getString("featured_products"), FEATURED), rs.getString("created_by"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
