package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The detail page's Overview and Analytics numbers for one window.
 *
 * @param kpis            pre-orders, cancelled, customers, units, revenue, average order, late adds
 * @param byDay           paid pre-orders per day from the opening day to two days after close (or today)
 * @param byChannel       pre-orders per attribution channel, most first
 * @param byCampaign      pre-orders that came from one of the window's own messages, per UTM source, medium,
 *                        campaign and content ({@link PreorderUtm}), most first
 * @param topProducts     the 8 products with the most units
 * @param fulfilmentSplit door vs pickup
 * @param placements      per placement: creative present and attributed pre-orders
 */
public record PreorderSummary(Kpis kpis, List<DayCount> byDay, List<ChannelCount> byChannel,
                              List<CampaignCount> byCampaign, List<ProductUnits> topProducts,
                              FulfilmentSplit fulfilmentSplit, List<PlacementRow> placements) {

    public static final int TOP_PRODUCTS = 8;

    /**
     * Headline numbers.
     *
     * @param preorders      paid + late adds
     * @param cancelled      cancelled orders
     * @param customers      unique customers among pre-orders
     * @param units          units to pick
     * @param revenueMinor   pre-order revenue including delivery fees
     * @param avgOrderMinor  revenue / pre-orders (null without pre-orders)
     * @param lateAdds       late adds by customer service
     * @param deliveryMinor  delivery fees included in the revenue
     * @param currency       ISO 4217
     */
    public record Kpis(int preorders, int cancelled, int customers, int units, long revenueMinor, Long avgOrderMinor,
                       int lateAdds, long deliveryMinor, String currency) {
    }

    /**
     * @param date       local day
     * @param orders     paid pre-orders placed that day
     * @param afterClose the day starts after the window closed (late adds; gold bars)
     */
    public record DayCount(LocalDate date, int orders, boolean afterClose) {
    }

    public record ChannelCount(AttributionChannel channel, String label, int orders) {
    }

    /** Orders credited to one announcement campaign: the UTM tags of the message link the shopper came from. */
    public record CampaignCount(String utmSource, String utmMedium, String utmCampaign, String utmContent, int orders) {
    }

    public record ProductUnits(String atin, String name, String seller, int units) {
    }

    /**
     * @param door      door pre-orders
     * @param pickup    pickup pre-orders
     * @param doorPct   share of door (0–100, null without pre-orders)
     * @param pickupPct share of pickup
     */
    public record FulfilmentSplit(int door, int pickup, Integer doorPct, Integer pickupPct) {
    }

    /**
     * @param key         placement key (hero, ribbon, pdp, story, feed, email)
     * @param name        display name
     * @param creative    creative (or ribbon text) present
     * @param impressions impressions (null until placement tracking reports them)
     * @param clicks      clicks (null until reported)
     * @param ctrPct      clicks / impressions × 100 (null without impressions)
     * @param orders      pre-orders attributed to the placement's channels
     */
    public record PlacementRow(String key, String name, boolean creative, Long impressions, Long clicks,
                               Double ctrPct, int orders) {
    }

    /** A placement, its creative slot (null = ribbon text) and the channels credited to it. */
    private record Spot(String key, String name, CreativeSlot slot, Set<AttributionChannel> credited) {
    }

    /** The design's analytics table. */
    private static final List<Spot> PLACEMENTS = List.of(
            new Spot("hero", "Storefront hero", CreativeSlot.HERO, Set.of(AttributionChannel.SITE_HERO)),
            new Spot("ribbon", "Header ribbon (text)", null, Set.of(AttributionChannel.SITE_RIBBON)),
            new Spot("pdp", "Product page banner", CreativeSlot.PDP, Set.of(AttributionChannel.SITE_PDP)),
            new Spot("story", "Social story", CreativeSlot.STORY,
                    Set.of(AttributionChannel.INSTAGRAM, AttributionChannel.TIKTOK, AttributionChannel.WHATSAPP)),
            new Spot("feed", "Social feed", CreativeSlot.FEED, Set.of(AttributionChannel.FACEBOOK)),
            new Spot("email", "Email header", CreativeSlot.EMAIL, Set.of(AttributionChannel.EMAIL)));

    /** Computes the summary of {@code window} from its projected orders at {@code now}. */
    public static PreorderSummary of(PreorderWindow window, List<PreorderOrder> orders, Instant now) {
        List<PreorderOrder> paid = orders.stream().filter(PreorderOrder::counts).toList();
        int cancelled = orders.size() - paid.size();
        Set<String> customers = new HashSet<>();
        long revenue = 0;
        long delivery = 0;
        int units = 0;
        int late = 0;
        for (PreorderOrder o : paid) {
            customers.add(o.customerId() != null ? o.customerId() : o.customerName());
            revenue += o.totalMinor();
            delivery += o.deliveryFeeMinor();
            units += o.units();
            if (o.status() == PreorderOrderStatus.LATE_ADD) {
                late++;
            }
        }
        Kpis kpis = new Kpis(paid.size(), cancelled, customers.size(), units, revenue,
                paid.isEmpty() ? null : Math.round((double) revenue / paid.size()), late, delivery,
                window.details().currency());

        Map<AttributionChannel, Integer> channels = new EnumMap<>(AttributionChannel.class);
        int door = 0;
        for (PreorderOrder o : paid) {
            channels.merge(o.channel(), 1, Integer::sum);
            if (o.fulfilment() == PreorderFulfilment.DOOR) {
                door++;
            }
        }
        List<ChannelCount> byChannel = channels.entrySet().stream()
                .sorted(Map.Entry.<AttributionChannel, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new ChannelCount(e.getKey(), e.getKey().label(), e.getValue())).toList();
        List<CampaignCount> byCampaign = campaigns(window, paid);
        List<ProductUnits> top = productUnits(paid).stream().limit(TOP_PRODUCTS).toList();
        int pickup = paid.size() - door;
        FulfilmentSplit split = new FulfilmentSplit(door, pickup,
                paid.isEmpty() ? null : (int) Math.round(door * 100.0 / paid.size()),
                paid.isEmpty() ? null : (int) Math.round(pickup * 100.0 / paid.size()));

        List<PlacementRow> rows = new ArrayList<>();
        for (Spot p : PLACEMENTS) {
            boolean present = p.slot() == null ? !window.details().ribbon().isBlank()
                    : window.creatives().containsKey(p.slot());
            int n = (int) paid.stream().filter(o -> p.credited().contains(o.channel())).count();
            rows.add(new PlacementRow(p.key(), p.name(), present, null, null, null, n));
        }
        return new PreorderSummary(kpis, byDay(window, paid, now), byChannel, byCampaign, top, split, rows);
    }

    /** Paid orders per UTM campaign of the window's own messages, most orders first. */
    static List<CampaignCount> campaigns(PreorderWindow window, List<PreorderOrder> paid) {
        Map<PreorderUtm.Tags, Integer> counts = new LinkedHashMap<>();
        for (PreorderOrder o : paid) {
            PreorderUtm.ofTrackingTag(o.trackingTag(), window.code()).ifPresent(t -> counts.merge(t, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<PreorderUtm.Tags, Integer>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().source()).thenComparing(e -> e.getKey().content()))
                .map(e -> new CampaignCount(e.getKey().source(), e.getKey().medium(), e.getKey().campaign(),
                        e.getKey().content(), e.getValue())).toList();
    }

    /** Units per product over orders that count (picking list), most units first, then by name. */
    public static List<ProductUnits> productUnits(List<PreorderOrder> orders) {
        Map<String, ProductUnits> products = new LinkedHashMap<>();
        for (PreorderOrder o : orders) {
            if (!o.counts()) {
                continue;
            }
            for (PreorderLine l : o.lines()) {
                String key = l.atin() != null ? l.atin() : l.name();
                ProductUnits prev = products.get(key);
                products.put(key, new ProductUnits(l.atin(), l.name(), l.seller(),
                        (prev == null ? 0 : prev.units()) + l.qty()));
            }
        }
        return products.values().stream()
                .sorted(Comparator.comparingInt(ProductUnits::units).reversed().thenComparing(ProductUnits::name))
                .toList();
    }

    /** Days from the opening day to {@code min(now, closes + 2 days)}; days after close are flagged. */
    static List<DayCount> byDay(PreorderWindow window, List<PreorderOrder> paid, Instant now) {
        if (paid.isEmpty()) {
            return List.of();
        }
        ZoneId zone = window.details().timeZone();
        Instant closes = window.details().closesAt();
        LocalDate first = window.details().opensAt().atZone(zone).toLocalDate();
        Instant end = now.isBefore(closes.plus(Duration.ofDays(2))) ? now : closes.plus(Duration.ofDays(2));
        LocalDate last = end.atZone(zone).toLocalDate();
        if (last.isBefore(first)) {
            return List.of();
        }
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            counts.put(d, 0);
        }
        for (PreorderOrder o : paid) {
            LocalDate d = o.placedAt().atZone(zone).toLocalDate();
            counts.computeIfPresent(d, (k, v) -> v + 1);
        }
        List<DayCount> out = new ArrayList<>();
        counts.forEach((d, n) -> out.add(new DayCount(d, n, d.atStartOfDay(zone).toInstant().isAfter(closes))));
        return out;
    }
}
