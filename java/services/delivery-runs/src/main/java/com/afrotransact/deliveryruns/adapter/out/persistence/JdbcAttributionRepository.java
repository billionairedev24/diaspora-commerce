package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AttributionKind;
import com.afrotransact.deliveryruns.domain.UtmTags;
import com.afrotransact.deliveryruns.port.AttributionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAttributionRepository implements AttributionRepository {

    private final JdbcClient jdbc;

    JdbcAttributionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void count(LocalDate day, UUID locationId, UUID runId, UtmTags tags, AttributionKind kind,
                      int maxCombinations) {
        UUID runKey = runId == null ? NO_RUN : runId;
        UtmTags counted = tags;
        boolean known = jdbc.sql("""
                select exists (select 1 from run_attribution_stat
                    where day = :day and location_id = :location and utm_source = :source and utm_medium = :medium
                      and utm_campaign = :campaign)
                """).param("day", day).param("location", locationId).param("source", tags.source())
                .param("medium", tags.medium()).param("campaign", tags.campaign()).query(Boolean.class).single();
        if (!known) {
            long combinations = jdbc.sql("""
                    select count(*) from (select distinct utm_source, utm_medium, utm_campaign
                        from run_attribution_stat where day = :day and location_id = :location) c
                    """).param("day", day).param("location", locationId).query(Long.class).single();
            if (combinations >= maxCombinations) {
                counted = UtmTags.overflow();
            }
        }
        jdbc.sql("""
                insert into run_attribution_stat (day, location_id, run_key, utm_source, utm_medium, utm_campaign,
                    kind, n)
                values (:day, :location, :run, :source, :medium, :campaign, :kind, 1)
                on conflict (day, location_id, run_key, utm_source, utm_medium, utm_campaign, kind)
                do update set n = run_attribution_stat.n + 1
                """).param("day", day).param("location", locationId).param("run", runKey)
                .param("source", counted.source()).param("medium", counted.medium())
                .param("campaign", counted.campaign()).param("kind", kind.name()).update();
    }

    @Override
    public List<Row> rows(UUID locationId, UUID runId, LocalDate since) {
        return jdbc.sql("""
                select utm_source, utm_medium, utm_campaign,
                       coalesce(sum(n) filter (where kind = 'VISIT'), 0) as visits,
                       coalesce(sum(n) filter (where kind = 'SIGNUP'), 0) as signups
                from run_attribution_stat
                where location_id = :location and day >= :since
                  and (cast(:run as uuid) is null or run_key = cast(:run as uuid))
                group by utm_source, utm_medium, utm_campaign
                order by visits desc, signups desc, utm_source, utm_medium, utm_campaign
                """).param("location", locationId).param("since", since).param("run", runId)
                .query((rs, n) -> new Row(new UtmTags(rs.getString("utm_source"), rs.getString("utm_medium"),
                        rs.getString("utm_campaign")), rs.getLong("visits"), rs.getLong("signups"))).list();
    }

    @Override
    public void remember(UUID subscriptionId, UUID runId, UtmTags tags) {
        jdbc.sql("""
                insert into subscription_attribution (subscription_id, run_key, utm_source, utm_medium, utm_campaign)
                values (:id, :run, :source, :medium, :campaign)
                on conflict (subscription_id) do update set run_key = excluded.run_key,
                    utm_source = excluded.utm_source, utm_medium = excluded.utm_medium,
                    utm_campaign = excluded.utm_campaign
                """).param("id", subscriptionId).param("run", runId == null ? NO_RUN : runId)
                .param("source", tags.source()).param("medium", tags.medium()).param("campaign", tags.campaign())
                .update();
    }

    @Override
    public Optional<Remembered> take(UUID subscriptionId) {
        return jdbc.sql("""
                delete from subscription_attribution where subscription_id = :id
                returning run_key, utm_source, utm_medium, utm_campaign
                """).param("id", subscriptionId).query((rs, n) -> {
                    UUID run = rs.getObject("run_key", UUID.class);
                    return new Remembered(NO_RUN.equals(run) ? null : run, new UtmTags(rs.getString("utm_source"),
                            rs.getString("utm_medium"), rs.getString("utm_campaign")));
                }).optional();
    }

    @Override
    public void recordOrder(String orderId, String creative, String src, Instant placedAt) {
        jdbc.sql("""
                insert into order_attribution (order_id, creative, src, placed_at)
                values (:order, :creative, :src, :placedAt) on conflict (order_id) do nothing
                """).param("order", orderId).param("creative", creative).param("src", src)
                .param("placedAt", Sql.ts(placedAt)).update();
    }

    @Override
    public Map<String, Integer> runOrdersByCreative(Collection<String> creatives) {
        Map<String, Integer> out = new HashMap<>();
        if (creatives.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select a.creative, count(distinct o.order_id) as n
                from order_attribution a join run_order o on o.order_id = a.order_id
                where a.creative in (:creatives) and o.status in ('RESERVED', 'CONFIRMED')
                group by a.creative
                """).param("creatives", List.copyOf(creatives)).query((rs, n) -> {
                    out.put(rs.getString("creative"), rs.getInt("n"));
                    return null;
                }).list();
        return out;
    }
}
