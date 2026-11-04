package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class PreorderSummaryTest {

    private static final PreorderLine EGUSI = new PreorderLine("EGU", "Egusi", "seller-1", 2);
    private static final PreorderLine GARRI = new PreorderLine("GAR", "Garri", "seller-2", 1);

    @Test
    void kpisChannelsProductsSplitAndPlacements() {
        PreorderWindow w = Preorders.published();
        Instant day1 = Instant.parse("2026-10-06T16:00:00Z");
        Instant day2 = Instant.parse("2026-10-07T16:00:00Z");
        Instant afterClose = Instant.parse("2026-10-23T18:00:00Z");
        List<PreorderOrder> orders = List.of(
                Preorders.order(w.id(), "1001", "c1", AttributionChannel.SITE_HERO, PreorderFulfilment.DOOR, day1,
                        PreorderOrderStatus.PAID, EGUSI),
                Preorders.order(w.id(), "1002", "c1", AttributionChannel.INSTAGRAM, PreorderFulfilment.PICKUP, day1,
                        PreorderOrderStatus.PAID, EGUSI, GARRI),
                Preorders.order(w.id(), "1003", "c2", AttributionChannel.SITE_HERO, PreorderFulfilment.DOOR, day2,
                        PreorderOrderStatus.CANCELLED, GARRI),
                Preorders.order(w.id(), "L90", "c3", AttributionChannel.AGENT_LATE, PreorderFulfilment.DOOR,
                        afterClose, PreorderOrderStatus.LATE_ADD, GARRI));

        PreorderSummary s = PreorderSummary.of(w, orders, Instant.parse("2026-10-30T00:00:00Z"));

        PreorderSummary.Kpis k = s.kpis();
        assertThat(k.preorders()).isEqualTo(3);
        assertThat(k.cancelled()).isEqualTo(1);
        assertThat(k.customers()).isEqualTo(2);
        assertThat(k.units()).isEqualTo(2 + 3 + 1);
        // 1001: 2000 + 799; 1002: 3000 + 0 (pickup); L90: 1000 + 799.
        assertThat(k.revenueMinor()).isEqualTo(2799 + 3000 + 1799);
        assertThat(k.deliveryMinor()).isEqualTo(799 + 799);
        assertThat(k.avgOrderMinor()).isEqualTo(Math.round(7598 / 3.0));
        assertThat(k.lateAdds()).isEqualTo(1);
        assertThat(k.currency()).isEqualTo("USD");

        assertThat(s.byChannel()).extracting(PreorderSummary.ChannelCount::channel, PreorderSummary.ChannelCount::orders)
                .containsExactly(tuple(AttributionChannel.SITE_HERO, 1), tuple(AttributionChannel.INSTAGRAM, 1),
                        tuple(AttributionChannel.AGENT_LATE, 1));
        assertThat(s.topProducts()).extracting(PreorderSummary.ProductUnits::name, PreorderSummary.ProductUnits::units)
                .containsExactly(tuple("Egusi", 4), tuple("Garri", 2));
        assertThat(s.fulfilmentSplit()).isEqualTo(new PreorderSummary.FulfilmentSplit(2, 1, 67, 33));
        assertThat(s.placements()).extracting(PreorderSummary.PlacementRow::key, PreorderSummary.PlacementRow::creative,
                PreorderSummary.PlacementRow::orders).containsExactly(tuple("hero", true, 1),
                tuple("ribbon", true, 0), tuple("pdp", false, 0), tuple("story", false, 1), tuple("feed", false, 0),
                tuple("email", false, 0));
    }

    @Test
    void ordersFromTheWindowsOwnMessagesAreCreditedToTheirUtmCampaign() {
        PreorderWindow w = Preorders.published();
        Instant day1 = Instant.parse("2026-10-06T16:00:00Z");
        PreorderOrder announce = Preorders.order(w.id(), "1", "c1", AttributionChannel.EMAIL, PreorderFulfilment.DOOR,
                day1, PreorderOrderStatus.PAID, EGUSI);
        PreorderOrder announce2 = Preorders.order(w.id(), "2", "c2", AttributionChannel.EMAIL, PreorderFulfilment.DOOR,
                day1, PreorderOrderStatus.PAID, EGUSI);
        PreorderOrder lastCall = Preorders.order(w.id(), "3", "c3", AttributionChannel.SMS, PreorderFulfilment.DOOR,
                day1, PreorderOrderStatus.PAID, EGUSI);
        PreorderOrder push = Preorders.order(w.id(), "4", "c4", AttributionChannel.APP, PreorderFulfilment.DOOR, day1,
                PreorderOrderStatus.PAID, EGUSI);
        PreorderOrder story = Preorders.order(w.id(), "5", "c5", AttributionChannel.INSTAGRAM, PreorderFulfilment.DOOR,
                day1, PreorderOrderStatus.PAID, EGUSI);
        PreorderOrder cancelled = Preorders.order(w.id(), "6", "c6", AttributionChannel.EMAIL, PreorderFulfilment.DOOR,
                day1, PreorderOrderStatus.CANCELLED, EGUSI);
        PreorderOrder untagged = Preorders.order(w.id(), "7", "c7", AttributionChannel.SITE_HERO,
                PreorderFulfilment.DOOR, day1, PreorderOrderStatus.PAID, EGUSI);

        PreorderSummary s = PreorderSummary.of(w, List.of(
                Preorders.tagged(announce, "src=email&cr=announce&po=LUB-7"),
                Preorders.tagged(announce2, "src=email&cr=announce&po=LUB-7"),
                Preorders.tagged(lastCall, "src=sms&cr=last&po=LUB-7"),
                Preorders.tagged(push, "src=app&cr=remind&po=LUB-7"),
                Preorders.tagged(story, "src=ig&cr=story&po=LUB-7"),
                Preorders.tagged(cancelled, "src=email&cr=announce&po=LUB-7"),
                untagged), Instant.parse("2026-10-30T00:00:00Z"));

        assertThat(s.byCampaign()).extracting(PreorderSummary.CampaignCount::utmSource,
                PreorderSummary.CampaignCount::utmMedium, PreorderSummary.CampaignCount::utmCampaign,
                PreorderSummary.CampaignCount::utmContent, PreorderSummary.CampaignCount::orders)
                .containsExactly(tuple("email", "email", "preorder-LUB-7", "announce", 2),
                        tuple("push", "push", "preorder-LUB-7", "remind", 1),
                        tuple("sms", "sms", "preorder-LUB-7", "last", 1));
    }

    @Test
    void ordersByDayRunFromOpeningToTwoDaysAfterCloseAndFlagLateDays() {
        PreorderWindow w = Preorders.published();
        List<PreorderOrder> orders = List.of(
                Preorders.order(w.id(), "1", "c1", AttributionChannel.SMS, PreorderFulfilment.DOOR,
                        Instant.parse("2026-10-06T16:00:00Z"), PreorderOrderStatus.PAID, EGUSI),
                Preorders.order(w.id(), "2", "c2", AttributionChannel.SMS, PreorderFulfilment.DOOR,
                        Instant.parse("2026-10-06T23:00:00Z"), PreorderOrderStatus.PAID, EGUSI),
                Preorders.order(w.id(), "3", "c3", AttributionChannel.AGENT_LATE, PreorderFulfilment.DOOR,
                        Instant.parse("2026-10-23T18:00:00Z"), PreorderOrderStatus.LATE_ADD, EGUSI));
        List<PreorderSummary.DayCount> days = PreorderSummary.of(w, orders, Instant.parse("2026-11-30T00:00:00Z"))
                .byDay();
        // Oct 6 … Oct 24 (closes Oct 22 23:59 local + 2 days).
        assertThat(days).hasSize(19);
        assertThat(days.get(0)).isEqualTo(new PreorderSummary.DayCount(LocalDate.parse("2026-10-06"), 2, false));
        assertThat(days.get(16).date()).isEqualTo(LocalDate.parse("2026-10-22"));
        assertThat(days.get(16).afterClose()).isFalse();
        assertThat(days.get(17)).isEqualTo(new PreorderSummary.DayCount(LocalDate.parse("2026-10-23"), 1, true));

        List<PreorderSummary.DayCount> sofar = PreorderSummary.of(w, orders, Instant.parse("2026-10-08T12:00:00Z"))
                .byDay();
        assertThat(sofar).hasSize(3);
        assertThat(PreorderSummary.of(w, List.of(), Instant.parse("2026-10-08T12:00:00Z")).byDay()).isEmpty();
        assertThat(PreorderSummary.of(w, List.of(), Instant.parse("2026-10-08T12:00:00Z")).kpis().avgOrderMinor())
                .isNull();
        assertThat(Duration.between(Preorders.OPENS, Preorders.CLOSES).toDays()).isEqualTo(16);
    }

    @Test
    void cancellingPackagesCancelsTheOrderWhenNoneIsLeft() {
        PreorderWindow w = Preorders.published();
        PreorderPackage a = new PreorderPackage("s1", "seller-1", 2799, 799, List.of(EGUSI), false);
        PreorderPackage b = new PreorderPackage("s2", "seller-2", 1000, 0, List.of(GARRI), false);
        PreorderOrder o = new PreorderOrder("o1", "LUB-7-1001", w.id(), "c1", null, null, null, null, null,
                Preorders.OPENS, null, null, List.of(a, b), null, "USD", null, null, Preorders.OPENS, Preorders.OPENS,
                0);
        assertThat(o.totalMinor()).isEqualTo(3799);
        PreorderOrder one = o.cancelShipment("s1", Preorders.OPENS);
        assertThat(one.status()).isEqualTo(PreorderOrderStatus.PAID);
        assertThat(one.totalMinor()).isEqualTo(1000);
        assertThat(one.units()).isEqualTo(1);
        PreorderOrder both = one.cancelShipment("s2", Preorders.OPENS);
        assertThat(both.status()).isEqualTo(PreorderOrderStatus.CANCELLED);
        assertThat(both.totalMinor()).as("a cancelled order keeps its original total").isEqualTo(3799);
        assertThat(o.cancelShipment("unknown", Preorders.OPENS)).isSameAs(o);
    }

    @Test
    void attributionChannelsComeFromSourceCodes() {
        assertThat(AttributionChannel.fromSource("ig")).isEqualTo(AttributionChannel.INSTAGRAM);
        assertThat(AttributionChannel.fromSource("RIBBON")).isEqualTo(AttributionChannel.SITE_RIBBON);
        assertThat(AttributionChannel.fromSource("agent")).isEqualTo(AttributionChannel.AGENT_LATE);
        assertThat(AttributionChannel.fromSource(null)).isEqualTo(AttributionChannel.SITE_DIRECT);
        assertThat(AttributionChannel.fromSource("newsletter")).isEqualTo(AttributionChannel.SITE_DIRECT);
        assertThat(AttributionChannel.trackingTag("ig", "story", "LUB-7")).isEqualTo("src=ig&cr=story&po=LUB-7");
        assertThat(AttributionChannel.trackingTag(null, null, "LUB-7")).isEqualTo("src=direct&po=LUB-7");
    }
}
