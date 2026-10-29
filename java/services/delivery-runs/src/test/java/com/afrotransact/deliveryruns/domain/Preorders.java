package com.afrotransact.deliveryruns.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Lubbock Pre-order #7 of the design (opens Oct 6 09:00, closes Oct 22 23:59 CDT, delivers Sun Oct 25). */
final class Preorders {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** The default rules (USD) plus a Canadian door fee of $9.99 within 100 miles. */
    static DeliveryFees feesWithCad() {
        List<DeliveryFeeRule> rules = new ArrayList<>(DeliveryFees.DEFAULT.rules());
        rules.add(new DeliveryFeeRule(UUID.randomUUID(), FeeMethod.AFROTRANSACT_DELIVERY, "CAD",
                List.of(new FeeBand(BigDecimal.ZERO, BigDecimal.valueOf(100), 999)), 0, 0, 0, null, 1, Instant.EPOCH,
                "test", "test"));
        return new DeliveryFees(rules, WindowDistances.UNKNOWN);
    }
    static final Instant OPENS = Instant.parse("2026-10-06T14:00:00Z");
    static final Instant CLOSES = Instant.parse("2026-10-23T04:59:00Z");
    static final LocalDate DELIVERS = LocalDate.parse("2026-10-25");
    static final CreativeRef HERO = new CreativeRef(UUID.randomUUID(), "https://cdn.test/hero.jpg", 1920, 640);

    private Preorders() {
    }

    static WindowDetails details(List<String> targets) {
        return new WindowDetails("Lubbock Pre-order #7", targets.stream().map(AreaKey::parse).toList(), OPENS, CLOSES,
                DELIVERS, CHICAGO, "Lubbock pre-order is open",
                "Fresh egusi, garri and palm oil, delivered to Lubbock on Sunday, Oct 25.",
                "AfroTransact: pre-order open. afrt.co/pre Reply STOP to opt out",
                Set.of("Pantry", "Spices", "Snacks", "Oils"), true, true, null, null, true,
                new Money(20_000, "USD"), EnumSet.allOf(CommKind.class), null, null, null);
    }

    static WindowDetails lubbock() {
        return details(List.of("US/TX/Lubbock"));
    }

    /** A published Lubbock window with its hero. */
    static PreorderWindow published() {
        return published(lubbock());
    }

    static PreorderWindow published(WindowDetails d) {
        return new PreorderWindow(UUID.randomUUID(), "LUB-7", d, Map.of(CreativeSlot.HERO, HERO), false, true, null,
                OPENS.minusSeconds(86_400), OPENS.minusSeconds(86_400), 0);
    }

    static PreorderWindow draft(WindowDetails d) {
        return PreorderWindow.draft(UUID.randomUUID(), "LUB-7", d, OPENS.minusSeconds(86_400));
    }

    static WindowDetails withDates(WindowDetails d, Instant opens, Instant closes, LocalDate delivers) {
        return new WindowDetails(d.name(), d.targets(), opens, closes, delivers, d.timeZone(), d.ribbon(),
                d.headline(), d.smsCopy(), d.categories(), d.door(), d.pickup(), d.pickupPointId(),
                d.pickupAddress(), d.showCount(), d.socialBudget(), d.comms(), d.doorFeeOverride(),
                d.pickupFeeOverride(), d.locationId());
    }

    static PreorderOrder order(UUID windowId, String id, String customer, AttributionChannel channel,
                               PreorderFulfilment fulfilment, Instant placedAt, PreorderOrderStatus status,
                               PreorderLine... lines) {
        long items = 0;
        for (PreorderLine l : lines) {
            items += 1000L * l.qty();
        }
        long fee = fulfilment == PreorderFulfilment.DOOR ? 799 : 0;
        return new PreorderOrder(id, "LUB-7-" + id, windowId, customer, "Name " + customer, null, null, null, null,
                placedAt, channel, null, List.of(new PreorderPackage("shp-" + id, "seller-1", items + fee, fee,
                List.of(lines), status == PreorderOrderStatus.CANCELLED)), fulfilment, "USD", status, null, placedAt,
                placedAt, 0);
    }

    /** {@code o} as the shopper placed it from a link: with the tracking tag of the order attribution. */
    static PreorderOrder tagged(PreorderOrder o, String trackingTag) {
        return new PreorderOrder(o.orderId(), o.number(), o.windowId(), o.customerId(), o.customerName(), o.phone(),
                o.email(), o.address(), o.deliveryNote(), o.placedAt(), o.channel(), trackingTag, o.packages(),
                o.fulfilment(), o.currency(), o.status(), o.note(), o.createdAt(), o.updatedAt(), o.version());
    }
}
