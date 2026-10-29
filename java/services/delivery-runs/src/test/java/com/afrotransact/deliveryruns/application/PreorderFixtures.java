package com.afrotransact.deliveryruns.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeRef;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DeliveryWindow;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Money;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.domain.WindowDetails;

/** Test fixtures for the pre-order application services: a Lubbock window around a fixed close time. */
final class PreorderFixtures {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    static final Instant OPENS = Instant.parse("2026-10-06T14:00:00Z");
    static final Instant CLOSES = Instant.parse("2026-10-23T04:59:00Z");
    static final LocalDate DELIVERS = LocalDate.parse("2026-10-25");

    private PreorderFixtures() {
    }

    /** A published, active window with these comms switched on. */
    static PreorderWindow window(Set<CommKind> comms) {
        return window(comms, true, null);
    }

    static PreorderWindow window(Set<CommKind> comms, boolean active, UUID runId) {
        WindowDetails d = new WindowDetails("Lubbock Pre-order #7", List.of(AreaKey.parse("US/TX/Lubbock")), OPENS,
                CLOSES, DELIVERS, CHICAGO, "Lubbock pre-order is open", "Fresh egusi delivered",
                "AfroTransact: pre-order open. {link} Reply STOP to opt out", Set.of("Pantry"), true, true, null, null,
                true, new Money(20_000, "USD"), comms.isEmpty() ? EnumSet.noneOf(CommKind.class) : EnumSet.copyOf(comms),
                null, null, null);
        return new PreorderWindow(UUID.randomUUID(), "LUB-7", d,
                Map.of(CreativeSlot.HERO, new CreativeRef(UUID.randomUUID(), "https://cdn.test/hero.jpg", 1920, 640)),
                false, active, runId, OPENS.minus(Duration.ofDays(1)), OPENS.minus(Duration.ofDays(1)), 0);
    }

    static Run run() {
        Instant start = Instant.parse("2026-10-25T14:00:00Z");
        Instant cutoff = start.minus(Duration.ofDays(1));
        RunTerms terms = new RunTerms(new DeliveryWindow(start, start.plus(Duration.ofHours(12))),
                cutoff, null, cutoff.plus(Duration.ofHours(2)), 799, "USD", 1, Capacity.UNLIMITED,
                DropMode.DOORSTEP, null, CutoffPolicy.CANCEL, 1);
        return Run.confirmedFromWindow(UUID.randomUUID(), UUID.randomUUID(), terms, null, 2, 2, CLOSES);
    }

    static RunOrder slot(Run run, String orderId, String customer, String handoverCode) {
        return RunOrder.reserve(UUID.randomUUID(), run.id(), orderId, customer, 1, null, CLOSES)
                .confirm(handoverCode, CLOSES);
    }

    static PreorderOrder order(PreorderWindow w, String orderId, String customer, PreorderFulfilment fulfilment,
                               PreorderOrderStatus status) {
        PreorderLine line = new PreorderLine("EGU", "Egusi", "seller-1", "Seller One", 2);
        return new PreorderOrder(orderId, "LUB-7-" + orderId, w.id(), customer, "Name " + customer, null, null, null,
                null, OPENS.plusSeconds(60), AttributionChannel.SITE_DIRECT, null,
                List.of(new PreorderPackage("shp-" + orderId, "seller-1", 2799, 799, List.of(line),
                        status == PreorderOrderStatus.CANCELLED)),
                fulfilment, "USD", status, null, OPENS, OPENS, 0);
    }
}
