package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.ChannelConsent;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.domain.SubscriptionStatus;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.MonthlyCount;
import com.afrotransact.deliveryruns.port.NetGrowth;
import com.afrotransact.deliveryruns.port.SubscriberCounts;
import com.afrotransact.deliveryruns.port.SubscriptionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSubscriptionRepository implements SubscriptionRepository {

    private final JdbcClient jdbc;

    JdbcSubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Subscription insert(Subscription s) {
        jdbc.sql("""
                insert into subscription (id, location_id, user_id, email, status, confirm_token_hash,
                    confirm_expires_at, unsubscribe_token, created_at, confirmed_at, updated_at, version)
                values (:id, :locationId, :userId, :email, :status, :hash, :expires, :unsub, :createdAt, :confirmedAt,
                    :updatedAt, 0)
                """)
                .param("id", s.id())
                .param("locationId", s.locationId())
                .param("userId", s.userId())
                .param("email", s.email())
                .param("status", s.status().name())
                .param("hash", s.confirmTokenHash())
                .param("expires", Sql.ts(s.confirmExpiresAt()))
                .param("unsub", s.unsubscribeToken())
                .param("createdAt", Sql.ts(s.createdAt()))
                .param("confirmedAt", Sql.ts(s.confirmedAt()))
                .param("updatedAt", Sql.ts(s.updatedAt()))
                .update();
        saveConsents(s, Map.of(), null);
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Subscription update(Subscription s) {
        Subscription before = findById(s.id()).orElseThrow();
        int rows = jdbc.sql("""
                update subscription set status = :status, confirm_token_hash = :hash, confirm_expires_at = :expires,
                    confirmed_at = :confirmedAt, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", s.status().name())
                .param("hash", s.confirmTokenHash())
                .param("expires", Sql.ts(s.confirmExpiresAt()))
                .param("confirmedAt", Sql.ts(s.confirmedAt()))
                .param("updatedAt", Sql.ts(s.updatedAt()))
                .param("id", s.id())
                .param("version", s.version())
                .update();
        if (rows == 0) {
            throw new ConcurrentUpdateException("Subscription changed; reload and retry");
        }
        saveConsents(s, before.consents(), before.status());
        return findById(s.id()).orElseThrow();
    }

    @Override
    public Optional<Subscription> findById(UUID id) {
        return one("select * from subscription where id = :v", id);
    }

    @Override
    public Optional<Subscription> findByUser(UUID locationId, String userId) {
        return jdbc.sql("select * from subscription where location_id = :l and user_id = :u")
                .param("l", locationId).param("u", userId).query(this::map).optional();
    }

    @Override
    public Optional<Subscription> findByEmail(UUID locationId, String email) {
        return jdbc.sql("select * from subscription where location_id = :l and email = :e")
                .param("l", locationId).param("e", email).query(this::map).optional();
    }

    @Override
    public Optional<Subscription> findByConfirmTokenHash(String tokenHash) {
        return one("select * from subscription where confirm_token_hash = :v", tokenHash);
    }

    @Override
    public Optional<Subscription> findByUnsubscribeToken(String token) {
        return one("select * from subscription where unsubscribe_token = :v", token);
    }

    @Override
    public List<Subscription> findAllByUser(String userId) {
        return jdbc.sql("select * from subscription where user_id = :u order by created_at").param("u", userId)
                .query(this::map).list();
    }

    @Override
    public List<SubscriberCounts> counts(UUID locationId) {
        String where = locationId == null ? "" : " where s.location_id = :l";
        Map<UUID, long[]> totals = new LinkedHashMap<>();
        JdbcClient.StatementSpec totalsSql = jdbc.sql("""
                select s.location_id, count(*) as total,
                    count(*) filter (where s.status = 'ACTIVE') as active,
                    count(*) filter (where s.status = 'PENDING_CONFIRMATION') as pending
                from subscription s""" + where + " group by s.location_id order by s.location_id");
        if (locationId != null) {
            totalsSql = totalsSql.param("l", locationId);
        }
        totalsSql.query((rs, n) -> totals.put(rs.getObject("location_id", UUID.class),
                new long[] {rs.getLong("total"), rs.getLong("active"), rs.getLong("pending")})).list();
        Map<UUID, Map<Channel, Long>> channels = new LinkedHashMap<>();
        JdbcClient.StatementSpec channelSql = jdbc.sql("""
                select s.location_id, c.channel, count(*) as n
                from subscription s join subscription_consent c on c.subscription_id = s.id
                where s.status = 'ACTIVE' and c.withdrawn_at is null""" + (locationId == null ? "" : " and s.location_id = :l")
                + " group by s.location_id, c.channel");
        if (locationId != null) {
            channelSql = channelSql.param("l", locationId);
        }
        channelSql.query((rs, n) -> channels.computeIfAbsent(rs.getObject("location_id", UUID.class),
                k -> new EnumMap<>(Channel.class)).put(Channel.valueOf(rs.getString("channel")), rs.getLong("n")))
                .list();
        List<SubscriberCounts> out = new ArrayList<>();
        totals.forEach((id, t) -> {
            Map<Channel, Long> byChannel = new EnumMap<>(Channel.class);
            for (Channel c : Channel.values()) {
                byChannel.put(c, channels.getOrDefault(id, Map.of()).getOrDefault(c, 0L));
            }
            out.add(new SubscriberCounts(id, t[0], t[1], t[2], byChannel));
        });
        return out;
    }

    @Override
    public List<Subscription> audience(UUID locationId, Channel channel, UUID after, int limit) {
        return jdbc.sql("""
                select s.* from subscription s join subscription_consent c on c.subscription_id = s.id
                where s.location_id = :l and s.status = 'ACTIVE' and c.channel = :c and c.withdrawn_at is null
                    and (cast(:after as uuid) is null or s.id > cast(:after as uuid))
                order by s.id limit :limit
                """).param("l", locationId).param("c", channel.name()).param("after", after == null ? null
                        : after.toString()).param("limit", limit).query(JdbcSubscriptionRepository::bare).list();
    }

    @Override
    public List<MonthlyCount> growth(UUID locationId, Instant since) {
        return jdbc.sql("""
                select to_char(date_trunc('month', created_at at time zone 'UTC'), 'YYYY-MM') as month,
                    count(*) as n
                from subscription where location_id = :l and created_at >= :since
                group by 1 order by 1
                """).param("l", locationId).param("since", Sql.ts(since))
                .query((rs, n) -> new MonthlyCount(YearMonth.parse(rs.getString("month")), rs.getLong("n"))).list();
    }

    @Override
    public List<NetGrowth> netGrowth(UUID locationId, Instant since) {
        return jdbc.sql("""
                with joined as (
                    select to_char(date_trunc('month', created_at at time zone 'UTC'), 'YYYY-MM') as month,
                        count(*) as n
                    from subscription where location_id = :l and created_at >= :since
                    group by 1),
                ended as (
                    select to_char(date_trunc('month', e.at at time zone 'UTC'), 'YYYY-MM') as month, count(*) as n
                    from (select coalesce((select max(c.at) from consent_log c
                                           where c.subscription_id = s.id and c.action = 'WITHDRAWN'),
                                          s.updated_at) as at
                          from subscription s where s.location_id = :l and s.status = 'CANCELLED') e
                    where e.at >= :since
                    group by 1)
                select coalesce(joined.month, ended.month) as month, coalesce(joined.n, 0) as signups,
                    coalesce(ended.n, 0) as unsubscribes
                from joined full outer join ended on joined.month = ended.month
                order by 1
                """).param("l", locationId).param("since", Sql.ts(since))
                .query((rs, n) -> new NetGrowth(YearMonth.parse(rs.getString("month")), rs.getLong("signups"),
                        rs.getLong("unsubscribes"))).list();
    }

    /** Upserts consent rows and appends a consent_log entry for every grant, withdrawal and confirmation. */
    private void saveConsents(Subscription s, Map<Channel, ChannelConsent> before, SubscriptionStatus beforeStatus) {
        List<ChannelConsent> changed = s.consents().values().stream()
                .sorted((a, b) -> a.channel().compareTo(b.channel())).toList();
        for (ChannelConsent c : changed) {
            ChannelConsent old = before.get(c.channel());
            if (Objects.equals(old, c)) {
                continue;
            }
            jdbc.sql("""
                    insert into subscription_consent (subscription_id, channel, consented_at, source, withdrawn_at)
                    values (:id, :channel, :consentedAt, :source, :withdrawnAt)
                    on conflict (subscription_id, channel) do update set consented_at = excluded.consented_at,
                        source = excluded.source, withdrawn_at = excluded.withdrawn_at
                    """)
                    .param("id", s.id())
                    .param("channel", c.channel().name())
                    .param("consentedAt", Sql.ts(c.consentedAt()))
                    .param("source", c.source())
                    .param("withdrawnAt", Sql.ts(c.withdrawnAt()))
                    .update();
            boolean newGrant = c.granted() && (old == null || !old.granted()
                    || !old.consentedAt().equals(c.consentedAt()));
            if (newGrant) {
                log(s.id(), c.channel(), "GRANTED", c.source(), c.consentedAt());
            } else if (!c.granted() && (old == null || old.granted())) {
                log(s.id(), c.channel(), "WITHDRAWN", c.source(), c.withdrawnAt());
            }
        }
        if (beforeStatus == SubscriptionStatus.PENDING_CONFIRMATION && s.status() == SubscriptionStatus.ACTIVE) {
            for (Channel c : s.activeChannels().stream().sorted().toList()) {
                log(s.id(), c, "CONFIRMED", "double-opt-in", s.confirmedAt());
            }
        }
    }

    private void log(UUID id, Channel channel, String action, String source, Instant at) {
        jdbc.sql("insert into consent_log (subscription_id, channel, action, source, at) "
                        + "values (:id, :channel, :action, :source, :at)")
                .param("id", id).param("channel", channel.name()).param("action", action).param("source", source)
                .param("at", Sql.ts(at)).update();
    }

    private Optional<Subscription> one(String sql, Object value) {
        return jdbc.sql(sql).param("v", value).query(this::map).optional();
    }

    /** Without consent rows (audience pages only need who to message). */
    private static Subscription bare(ResultSet rs, int rowNum) throws SQLException {
        return new Subscription(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                rs.getString("user_id"), rs.getString("email"), SubscriptionStatus.valueOf(rs.getString("status")),
                Map.of(), null, null, rs.getString("unsubscribe_token"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "confirmed_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private Subscription map(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<Channel, ChannelConsent> consents = new EnumMap<>(Channel.class);
        jdbc.sql("select * from subscription_consent where subscription_id = :id").param("id", id)
                .query((r, n) -> consents.put(Channel.valueOf(r.getString("channel")),
                        new ChannelConsent(Channel.valueOf(r.getString("channel")), Sql.instant(r, "consented_at"),
                                r.getString("source"), Sql.instant(r, "withdrawn_at"))))
                .list();
        return new Subscription(id, rs.getObject("location_id", UUID.class), rs.getString("user_id"),
                rs.getString("email"), SubscriptionStatus.valueOf(rs.getString("status")), consents,
                rs.getString("confirm_token_hash"), Sql.instant(rs, "confirm_expires_at"),
                rs.getString("unsubscribe_token"), Sql.instant(rs, "created_at"), Sql.instant(rs, "confirmed_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
