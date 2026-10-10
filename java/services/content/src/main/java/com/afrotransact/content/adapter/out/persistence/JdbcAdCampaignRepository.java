package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignContent;
import com.afrotransact.content.domain.ads.CampaignStatus;
import com.afrotransact.content.domain.ads.PauseReason;
import com.afrotransact.content.port.AdCampaignRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdCampaignRepository implements AdCampaignRepository {

    private static final String SELECT = """
            select id, seller_org_id, advertiser, name, market, status, pause_reason, bid_minor, daily_budget_minor,
                   total_budget_minor, spent_minor, currency, keywords::text as keywords, categories::text as categories,
                   atins::text as atins, starts_at, ends_at, created_by, created_at, updated_at, version
            from ad_campaign
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcAdCampaignRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(Campaign c) {
        CampaignContent k = c.content();
        jdbc.sql("""
                insert into ad_campaign (id, seller_org_id, advertiser, name, market, status, pause_reason, bid_minor,
                    daily_budget_minor, total_budget_minor, spent_minor, currency, keywords, categories, atins, starts_at,
                    ends_at, created_by, created_at, updated_at, version)
                values (:id, :org, :advertiser, :name, :market, :status, :reason, :bid, :daily, :total, :spent, :currency,
                    cast(:keywords as jsonb), cast(:categories as jsonb), cast(:atins as jsonb), :starts, :ends, :by,
                    :created, :updated, 0)
                """)
                .param("id", c.id()).param("org", c.sellerOrgId()).param("advertiser", k.advertiser())
                .param("name", k.name()).param("market", k.market()).param("status", c.status().name())
                .param("reason", c.pauseReason() == null ? null : c.pauseReason().name()).param("bid", k.bidMinor())
                .param("daily", k.dailyBudgetMinor()).param("total", k.totalBudgetMinor()).param("spent", c.spentMinor())
                .param("currency", k.currency()).param("keywords", json.write(k.keywords()))
                .param("categories", json.write(k.categories())).param("atins", json.write(k.atins()))
                .param("starts", Timestamps.of(k.startsAt())).param("ends", Timestamps.of(k.endsAt()))
                .param("by", c.createdBy()).param("created", Timestamps.of(c.createdAt()))
                .param("updated", Timestamps.of(c.updatedAt()))
                .update();
    }

    @Override
    public boolean update(Campaign c) {
        CampaignContent k = c.content();
        return jdbc.sql("""
                update ad_campaign set advertiser = :advertiser, name = :name, market = :market, status = :status,
                    pause_reason = :reason, bid_minor = :bid, daily_budget_minor = :daily, total_budget_minor = :total,
                    spent_minor = :spent, currency = :currency, keywords = cast(:keywords as jsonb),
                    categories = cast(:categories as jsonb), atins = cast(:atins as jsonb), starts_at = :starts,
                    ends_at = :ends, updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("advertiser", k.advertiser()).param("name", k.name()).param("market", k.market())
                .param("status", c.status().name())
                .param("reason", c.pauseReason() == null ? null : c.pauseReason().name()).param("bid", k.bidMinor())
                .param("daily", k.dailyBudgetMinor()).param("total", k.totalBudgetMinor()).param("spent", c.spentMinor())
                .param("currency", k.currency()).param("keywords", json.write(k.keywords()))
                .param("categories", json.write(k.categories())).param("atins", json.write(k.atins()))
                .param("starts", Timestamps.of(k.startsAt())).param("ends", Timestamps.of(k.endsAt()))
                .param("updated", Timestamps.of(c.updatedAt())).param("id", c.id()).param("version", c.version())
                .update() == 1;
    }

    @Override
    public Optional<Campaign> findById(String id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Campaign> lock(String id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public List<Campaign> find(String sellerOrgId, CampaignStatus status, int limit) {
        return jdbc.sql(SELECT + """
                 where (cast(:org as varchar) is null or seller_org_id = :org)
                   and (cast(:status as varchar) is null or status = :status)
                 order by created_at desc, id limit :limit
                """)
                .param("org", sellerOrgId).param("status", status == null ? null : status.name())
                .param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Campaign> findActive(String market) {
        return jdbc.sql(SELECT + " where market = :market and status = 'ACTIVE' and spent_minor < total_budget_minor")
                .param("market", market).query(this::map).list();
    }

    private Campaign map(ResultSet rs, int row) throws SQLException {
        long daily = rs.getLong("daily_budget_minor");
        Long dailyBudget = rs.wasNull() ? null : daily;
        CampaignContent content = new CampaignContent(rs.getString("name"), rs.getString("advertiser"),
                rs.getString("market"), rs.getLong("bid_minor"), dailyBudget, rs.getLong("total_budget_minor"),
                rs.getString("currency"), json.strings(rs.getString("keywords")), json.strings(rs.getString("categories")),
                json.strings(rs.getString("atins")), Timestamps.instant(rs, "starts_at"), Timestamps.instant(rs, "ends_at"));
        String reason = rs.getString("pause_reason");
        return new Campaign(rs.getString("id"), rs.getString("seller_org_id"), content,
                CampaignStatus.valueOf(rs.getString("status")), reason == null ? null : PauseReason.valueOf(reason),
                rs.getLong("spent_minor"), rs.getString("created_by"), Timestamps.instant(rs, "created_at"),
                Timestamps.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
