package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.application.AdDeliveryService;
import com.afrotransact.content.application.ContentEvents;
import com.afrotransact.content.application.ContentPermissions;
import com.afrotransact.content.domain.ads.AdClick;
import com.afrotransact.content.domain.ads.PlacedOrder;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S127: campaigns, serving to search, click charging with pause at zero, impressions and 7-day attribution. */
class AdsIT extends ContentIntegrationTest {

    AdDeliveryService delivery;

    @BeforeEach
    void wireDelivery(@Autowired AdDeliveryService adDelivery) {
        this.delivery = adDelivery;
    }

    static Actor seller(String org) {
        return new Actor("owner-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of(ContentPermissions.ADS_MANAGE));
    }

    static Actor search() {
        return new Actor("search-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of(ContentPermissions.ADS_SERVE));
    }

    private static String campaign(String org, long bid, long total, String atin) {
        return """
                {"sellerOrgId":"%s","name":"Egusi always-on","advertiser":"Mama Tee","market":"US","bidMinor":%d,
                 "dailyBudgetMinor":%d,"totalBudgetMinor":%d,"currency":"USD","keywords":["Egusi","melon seeds"],
                 "categories":["Groceries › Pantry"],"atins":["%s"]}
                """.formatted(org, bid, total, total, atin);
    }

    private List<String> servingIds() {
        TestActors.as(search());
        HttpResponse<String> r = get("/v1/ads/serving?market=us");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        List<String> ids = new ArrayList<>();
        json(r).get("campaigns").forEach(c -> ids.add(c.get("id").asString()));
        return ids;
    }

    @Test
    void sellerCampaignServesIsChargedPerClickAndPausesAtZero() {
        String org = "org_ads_" + UUID.randomUUID().toString().substring(0, 8);
        TestActors.as(seller(org));
        HttpResponse<String> created = post("/v1/ads/campaigns", campaign(org, 40, 1000, "AT1EGU0001"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode c = json(created);
        String id = c.get("id").asString();
        assertThat(c.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(c.get("keywords").toString()).isEqualTo("[\"egusi\",\"melon seeds\"]");
        assertThat(c.get("remainingMinor").asLong()).isEqualTo(1000);

        // Another organisation's member cannot create or read it.
        TestActors.as(seller("org_other"));
        assertThat(post("/v1/ads/campaigns", campaign(org, 40, 1000, "AT1EGU0001")).statusCode()).isEqualTo(403);
        assertThat(get("/v1/ads/campaigns/" + id).statusCode()).isEqualTo(403);
        assertThat(json(get("/v1/ads/campaigns")).toString()).doesNotContain(id);

        // Bids are confidential: shoppers cannot read the serving list.
        TestActors.as(new Actor("cus_1", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        assertThat(get("/v1/ads/serving").statusCode()).isEqualTo(403);

        assertThat(servingIds()).contains(id);
        TestActors.as(search());
        JsonNode served = json(get("/v1/ads/serving?market=US")).get("campaigns");
        served.forEach(s -> {
            if (s.get("id").asString().equals(id)) {
                assertThat(s.get("bidMinor").asLong()).isEqualTo(40);
                assertThat(s.get("remainingMinor").asLong()).isEqualTo(1000);
                assertThat(s.get("atins").get(0).asString()).isEqualTo("AT1EGU0001");
            }
        });

        Instant now = Instant.now();
        delivery.impression(UUID.randomUUID(), id, now);
        UUID impression = UUID.randomUUID();
        delivery.impression(impression, id, now);
        delivery.impression(impression, id, now); // redelivered: counted once

        // 28 clicks at 35 = 980, then a click with 20 left is charged 20 and pauses the campaign.
        for (int i = 0; i < 28; i++) {
            AdDeliveryService.ClickOutcome o = delivery.click(UUID.randomUUID(), click(id, "clk-" + id + "-" + i, 35,
                    "cus_" + i, now));
            assertThat(o.chargedMinor()).isEqualTo(35);
        }
        AdClick lastClick = click(id, "clk-" + id + "-last", 35, "cus_buyer", now);
        AdDeliveryService.ClickOutcome last = delivery.click(UUID.randomUUID(), lastClick);
        assertThat(last.chargedMinor()).isEqualTo(20);
        assertThat(last.paused()).isTrue();
        // The same click again (new event id, same click id) is not charged twice.
        assertThat(delivery.click(UUID.randomUUID(), lastClick).recorded()).isFalse();

        TestActors.as(seller(org));
        JsonNode paused = json(get("/v1/ads/campaigns/" + id));
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("pauseReason").asString()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(paused.get("spentMinor").asLong()).isEqualTo(1000);
        assertThat(events(ContentEvents.ADS_CAMPAIGN_PAUSED, id)).isEqualTo(1);
        assertThat(lastEvent(ContentEvents.ADS_CAMPAIGN_PAUSED).get("reason").asString()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(post("/v1/ads/campaigns/" + id + "/resume", "").statusCode()).isEqualTo(409);
        assertThat(servingIds()).doesNotContain(id);

        // Attribution: the shopper who clicked buys the variant 2 days later from the same seller.
        String orderId = UUID.randomUUID().toString();
        PlacedOrder order = new PlacedOrder(orderId, "cus_buyer", now.plus(2, ChronoUnit.DAYS), List.of(
                new PlacedOrder.Line("l1", org, "AT1EGU0001V", 2 * 1099),
                new PlacedOrder.Line("l2", "org_someone_else", "AT1EGU0001V", 999),
                new PlacedOrder.Line("l3", org, "AT9OTHER01", 500)));
        UUID orderEvent = UUID.randomUUID();
        assertThat(delivery.orderPlaced(orderEvent, order)).isEqualTo(1);
        assertThat(delivery.orderPlaced(orderEvent, order)).isZero();
        // Outside the 7-day window nothing is attributed.
        assertThat(delivery.orderPlaced(UUID.randomUUID(), new PlacedOrder(UUID.randomUUID().toString(), "cus_buyer",
                now.plus(8, ChronoUnit.DAYS), List.of(new PlacedOrder.Line("l1", org, "AT1EGU0001V", 1099))))).isZero();

        TestActors.as(seller(org));
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        HttpResponse<String> report = get("/v1/ads/campaigns/" + id + "/report?from=" + today + "&to="
                + today.plusDays(2));
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        JsonNode total = json(report).get("total");
        assertThat(total.get("impressions").asLong()).isEqualTo(2);
        assertThat(total.get("clicks").asLong()).isEqualTo(29);
        assertThat(total.get("spendMinor").asLong()).isEqualTo(1000);
        assertThat(total.get("attributedOrders").asLong()).isEqualTo(1);
        assertThat(total.get("attributedSalesMinor").asLong()).isEqualTo(2198);
        assertThat(total.get("roas").asDouble()).isEqualTo(2.2);
        assertThat(total.get("ctr").asDouble()).isEqualTo(14.5);
        assertThat(json(report).get("days").size()).isEqualTo(3);

        // Raising the budget resumes it and it serves again.
        long version = json(get("/v1/ads/campaigns/" + id)).get("version").asLong();
        String raise = campaign(org, 40, 2000, "AT1EGU0001").replace("}", ",\"expectedVersion\":" + version + "}");
        HttpResponse<String> raised = put("/v1/ads/campaigns/" + id, raise);
        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(200);
        assertThat(json(raised).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(servingIds()).contains(id);
    }

    @Test
    void staffManageAnyCampaignAndValidationErrorsAreFieldLevel() {
        TestActors.as(TestActors.staff("ads-ops", ContentPermissions.ADS_EDIT));
        HttpResponse<String> bad = post("/v1/ads/campaigns", """
                {"sellerOrgId":"org_x","name":"x","advertiser":"A","bidMinor":5,"totalBudgetMinor":100,
                 "atins":["bad"]}
                """);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(json(bad).get("code").asString()).isEqualTo("content.invalid-campaign");
        assertThat(json(bad).get("fieldErrors").has("bidMinor")).isTrue();

        String id = json(post("/v1/ads/campaigns", campaign("org_staff_made", 30, 900, "AT1PLA0001"))).get("id").asString();
        assertThat(json(post("/v1/ads/campaigns/" + id + "/pause", "")).get("pauseReason").asString()).isEqualTo("MANUAL");
        assertThat(lastEvent(ContentEvents.ADS_CAMPAIGN_PAUSED).get("reason").asString()).isEqualTo("MANUAL");
        assertThat(servingIds()).doesNotContain(id);
        TestActors.as(TestActors.staff("ads-ops", ContentPermissions.ADS_EDIT));
        assertThat(json(post("/v1/ads/campaigns/" + id + "/resume", "")).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(json(post("/v1/ads/campaigns/" + id + "/end", "")).get("status").asString()).isEqualTo("ENDED");
        assertThat(post("/v1/ads/campaigns/" + id + "/resume", "").statusCode()).isEqualTo(409);
        TestActors.as(TestActors.staff("viewer", ContentPermissions.ADS_VIEW));
        assertThat(get("/v1/ads/campaigns?sellerOrgId=org_staff_made").statusCode()).isEqualTo(200);
        assertThat(post("/v1/ads/campaigns/" + id + "/pause", "").statusCode()).isEqualTo(403);
        assertThat(jdbc.sql("select count(*) from audit_log where entity_type = 'ad_campaign' and entity_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void chargedSpendIsReportedPerOrganisationAndDayForMeteredBilling() {
        String org = "org_spend_" + UUID.randomUUID().toString().substring(0, 8);
        TestActors.as(seller(org));
        String id = json(post("/v1/ads/campaigns", campaign(org, 50, 5000, "AT1SPD0001"))).get("id").asString();
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        delivery.click(UUID.randomUUID(), click(id, "clk-spend-" + id + "-1", 30, "cus_s1", now));
        delivery.click(UUID.randomUUID(), click(id, "clk-spend-" + id + "-2", 45, "cus_s2", now));
        // A redelivered click (same click id) is never charged twice, so the report is stable.
        delivery.click(UUID.randomUUID(), click(id, "clk-spend-" + id + "-2", 45, "cus_s2", now));

        // Sellers and shoppers cannot read other organisations' spend.
        assertThat(get("/v1/ads/spend?from=" + today + "&to=" + today).statusCode()).isEqualTo(403);

        TestActors.as(new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of(ContentPermissions.ADS_SPEND_READ)));
        HttpResponse<String> r = get("/v1/ads/spend?from=" + today.minusDays(1) + "&to=" + today);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        List<JsonNode> mine = new ArrayList<>();
        json(r).get("items").forEach(i -> {
            if (i.get("sellerOrgId").asString().equals(org)) {
                mine.add(i);
            }
        });
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().get("day").asString()).isEqualTo(today.toString());
        assertThat(mine.getFirst().get("currency").asString()).isEqualTo("USD");
        assertThat(mine.getFirst().get("clicks").asLong()).isEqualTo(2);
        assertThat(mine.getFirst().get("spendMinor").asLong()).isEqualTo(75);

        assertThat(get("/v1/ads/spend?from=" + today + "&to=" + today.minusDays(1)).statusCode()).isEqualTo(422);
        assertThat(get("/v1/ads/spend?from=" + today.minusDays(200) + "&to=" + today).statusCode()).isEqualTo(422);
        TestActors.as(search());
        assertThat(get("/v1/ads/spend?from=" + today + "&to=" + today).statusCode()).isEqualTo(403);
    }

    private static AdClick click(String campaignId, String clickId, long price, String customer, Instant at) {
        return new AdClick(clickId, campaignId, "AT1EGU0001", List.of("AT1EGU0001V"), customer, price, "US", "egusi", at);
    }
}
