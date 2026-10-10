package com.afrotransact.content.adapter.out.persistence;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.ads.AdClick;
import com.afrotransact.content.domain.ads.CampaignDay;
import com.afrotransact.content.port.AdStatsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdStatsRepository implements AdStatsRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcAdStatsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private void upsert(String campaignId, LocalDate day, long impressions, long clicks, long spend, long orders,
                        long sales) {
        jdbc.sql("""
                insert into ad_campaign_daily (campaign_id, day, impressions, clicks, spend_minor, attributed_orders,
                    attributed_sales_minor)
                values (:id, :day, :imp, :clicks, :spend, :orders, :sales)
                on conflict (campaign_id, day) do update set
                    impressions = ad_campaign_daily.impressions + excluded.impressions,
                    clicks = ad_campaign_daily.clicks + excluded.clicks,
                    spend_minor = ad_campaign_daily.spend_minor + excluded.spend_minor,
                    attributed_orders = ad_campaign_daily.attributed_orders + excluded.attributed_orders,
                    attributed_sales_minor = ad_campaign_daily.attributed_sales_minor + excluded.attributed_sales_minor
                """)
                .param("id", campaignId).param("day", Date.valueOf(day)).param("imp", impressions)
                .param("clicks", clicks).param("spend", spend).param("orders", orders).param("sales", sales)
                .update();
    }

    @Override
    public void addImpressions(String campaignId, LocalDate day, long count) {
        upsert(campaignId, day, count, 0, 0, 0, 0);
    }

    @Override
    public void addClick(String campaignId, LocalDate day, long chargedMinor) {
        upsert(campaignId, day, 0, 1, chargedMinor, 0, 0);
    }

    @Override
    public void addAttributed(String campaignId, LocalDate day, long orders, long salesMinor) {
        upsert(campaignId, day, 0, 0, 0, orders, salesMinor);
    }

    @Override
    public Map<String, Long> spendOn(Collection<String> campaignIds, LocalDate day) {
        Map<String, Long> out = new HashMap<>();
        if (campaignIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select campaign_id, spend_minor from ad_campaign_daily where day = :day and campaign_id in (:ids)")
                .param("day", Date.valueOf(day)).param("ids", List.copyOf(campaignIds))
                .query(rs -> {
                    out.put(rs.getString("campaign_id"), rs.getLong("spend_minor"));
                });
        return out;
    }

    @Override
    public boolean clickExists(String clickId) {
        return jdbc.sql("select count(*) from ad_click where click_id = :id").param("id", clickId)
                .query(Long.class).single() > 0;
    }

    @Override
    public boolean insertClick(AdClick c, long chargedMinor) {
        return jdbc.sql("""
                insert into ad_click (click_id, campaign_id, atin, variant_atins, customer_id, price_minor, charged_minor,
                    market, query, occurred_at)
                values (:id, :campaign, :atin, cast(:variants as jsonb), :customer, :price, :charged, :market, :query, :at)
                on conflict (click_id) do nothing
                """)
                .param("id", c.clickId()).param("campaign", c.campaignId()).param("atin", c.atin())
                .param("variants", json.write(c.variantAtins())).param("customer", c.customerId())
                .param("price", c.priceMinor()).param("charged", chargedMinor).param("market", c.market())
                .param("query", c.query() == null ? null : c.query().substring(0, Math.min(200, c.query().length())))
                .param("at", Timestamps.of(c.occurredAt()))
                .update() == 1;
    }

    @Override
    public Optional<AttributableClick> lastClick(String customerId, String sellerOrgId, String variantAtin,
                                                 Instant from, Instant to) {
        return jdbc.sql("""
                select c.click_id, c.campaign_id
                from ad_click c join ad_campaign k on k.id = c.campaign_id
                where c.customer_id = :customer and k.seller_org_id = :seller
                  and (c.atin = :variant or c.variant_atins @> jsonb_build_array(cast(:variant as text)))
                  and c.occurred_at >= :from and c.occurred_at <= :to
                order by c.occurred_at desc, c.click_id
                limit 1
                """)
                .param("customer", customerId).param("seller", sellerOrgId).param("variant", variantAtin)
                .param("from", Timestamps.of(from)).param("to", Timestamps.of(to))
                .query((rs, n) -> new AttributableClick(rs.getString("click_id"), rs.getString("campaign_id")))
                .optional();
    }

    @Override
    public boolean insertAttribution(String orderId, String lineId, AttributableClick click, long salesMinor,
                                     Instant at) {
        return jdbc.sql("""
                insert into ad_attribution (order_id, line_id, click_id, campaign_id, sales_minor, attributed_at)
                values (:order, :line, :click, :campaign, :sales, :at)
                on conflict (order_id, line_id) do nothing
                """)
                .param("order", orderId).param("line", lineId).param("click", click.clickId())
                .param("campaign", click.campaignId()).param("sales", salesMinor).param("at", Timestamps.of(at))
                .update() == 1;
    }

    @Override
    public List<OrgDaySpend> spendByOrg(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select k.seller_org_id, d.day, k.currency, sum(d.clicks) as clicks, sum(d.spend_minor) as spend
                from ad_campaign_daily d join ad_campaign k on k.id = d.campaign_id
                where d.day between :from and :to
                group by k.seller_org_id, d.day, k.currency
                having sum(d.spend_minor) > 0
                order by k.seller_org_id, d.day, k.currency
                """)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> new OrgDaySpend(rs.getString("seller_org_id"), rs.getDate("day").toLocalDate(),
                        rs.getString("currency"), rs.getLong("clicks"), rs.getLong("spend")))
                .list();
    }

    @Override
    public List<CampaignDay> days(String campaignId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select day, impressions, clicks, spend_minor, attributed_orders, attributed_sales_minor
                from ad_campaign_daily where campaign_id = :id and day between :from and :to order by day
                """)
                .param("id", campaignId).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> new CampaignDay(rs.getDate("day").toLocalDate(), rs.getLong("impressions"),
                        rs.getLong("clicks"), rs.getLong("spend_minor"), rs.getLong("attributed_orders"),
                        rs.getLong("attributed_sales_minor")))
                .list();
    }
}
