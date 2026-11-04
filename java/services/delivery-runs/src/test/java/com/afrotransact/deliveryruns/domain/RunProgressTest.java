package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S264: an order's package moves forward on its run from shipment events, never backwards, and runs count them. */
class RunProgressTest {

    private static final Instant T0 = Instant.parse("2026-10-24T14:00:00Z");

    private static RunOrder slot(String orderId) {
        return RunOrder.reserve(UUID.randomUUID(), UUID.randomUUID(), orderId, "cust-" + orderId, 1, null, T0)
                .confirm(null, T0);
    }

    @Test
    void shipmentStatusesMapToRunStages() {
        assertThat(RunOrderStage.ofShipmentStatus("picked_up")).contains(RunOrderStage.COLLECTED);
        assertThat(RunOrderStage.ofShipmentStatus("OUT_FOR_DELIVERY")).contains(RunOrderStage.OUT_FOR_DELIVERY);
        assertThat(RunOrderStage.ofShipmentStatus("delivery_failed")).contains(RunOrderStage.FAILED);
        assertThat(RunOrderStage.ofShipmentStatus("delivered")).contains(RunOrderStage.DELIVERED);
        assertThat(RunOrderStage.ofShipmentStatus("returned")).contains(RunOrderStage.RETURNED);
        for (String other : List.of("placed", "confirmed", "packed", "ready", "cancelled", "", "nonsense")) {
            assertThat(RunOrderStage.ofShipmentStatus(other)).isEqualTo(Optional.empty());
        }
        assertThat(RunOrderStage.ofShipmentStatus(null)).isEmpty();
    }

    @Test
    void anOrderMovesForwardThroughCollectedOnTheRoadAndDelivered() {
        RunOrder s = slot("o-1");
        assertThat(s.stage()).isEqualTo(RunOrderStage.PENDING);
        RunOrder collected = s.advance(RunOrderStage.COLLECTED, T0.plusSeconds(60), T0.plusSeconds(61));
        RunOrder out = collected.advance(RunOrderStage.OUT_FOR_DELIVERY, T0.plusSeconds(70), T0.plusSeconds(71));
        RunOrder done = out.advance(RunOrderStage.DELIVERED, T0.plusSeconds(3600), T0.plusSeconds(3601));
        assertThat(done.stage()).isEqualTo(RunOrderStage.DELIVERED);
        assertThat(done.stageAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(done.deliveredAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(collected.deliveredAt()).isNull();
    }

    @Test
    void repeatedAndLateEventsChangeNothing() {
        RunOrder delivered = slot("o-2").advance(RunOrderStage.DELIVERED, T0, T0);
        assertThat(delivered.advance(RunOrderStage.DELIVERED, T0.plusSeconds(5), T0.plusSeconds(5)))
                .isSameAs(delivered);
        assertThat(delivered.advance(RunOrderStage.COLLECTED, T0, T0)).isSameAs(delivered);
        assertThat(delivered.advance(RunOrderStage.FAILED, T0, T0)).isSameAs(delivered);
        RunOrder out = slot("o-3").advance(RunOrderStage.OUT_FOR_DELIVERY, T0, T0);
        assertThat(out.advance(RunOrderStage.COLLECTED, T0, T0)).isSameAs(out);
        assertThat(out.advance(RunOrderStage.OUT_FOR_DELIVERY, T0, T0)).isSameAs(out);
    }

    @Test
    void aFailedAttemptCanBeRetriedOrDeliveredButNotRolledBackToCollected() {
        RunOrder failed = slot("o-4").advance(RunOrderStage.OUT_FOR_DELIVERY, T0, T0)
                .advance(RunOrderStage.FAILED, T0.plusSeconds(10), T0.plusSeconds(10));
        assertThat(failed.stage()).isEqualTo(RunOrderStage.FAILED);
        assertThat(failed.advance(RunOrderStage.COLLECTED, T0, T0)).isSameAs(failed);
        assertThat(failed.advance(RunOrderStage.OUT_FOR_DELIVERY, T0, T0).stage())
                .isEqualTo(RunOrderStage.OUT_FOR_DELIVERY);
        assertThat(failed.advance(RunOrderStage.DELIVERED, T0, T0).stage()).isEqualTo(RunOrderStage.DELIVERED);
        assertThat(failed.advance(RunOrderStage.RETURNED, T0, T0).stage()).isEqualTo(RunOrderStage.RETURNED);
    }

    @Test
    void anEventWithoutATimeUsesTheTimeItWasReceived() {
        RunOrder d = slot("o-5").advance(RunOrderStage.DELIVERED, null, T0.plusSeconds(9));
        assertThat(d.deliveredAt()).isEqualTo(T0.plusSeconds(9));
    }

    @Test
    void releasedAndCancelledSlotsDoNotMove() {
        RunOrder cancelled = slot("o-6").cancel(T0);
        assertThat(cancelled.advance(RunOrderStage.DELIVERED, T0, T0)).isSameAs(cancelled);
    }

    @Test
    void deliveryProgressSurvivesCancellingTheSlot() {
        RunOrder collected = slot("o-7").advance(RunOrderStage.COLLECTED, T0, T0);
        assertThat(collected.cancel(T0).stage()).isEqualTo(RunOrderStage.COLLECTED);
    }

    @Test
    void progressCountsConfirmedOrdersPerStage() {
        RunOrder pending = slot("a");
        RunOrder collected = slot("b").advance(RunOrderStage.COLLECTED, T0, T0);
        RunOrder out = slot("c").advance(RunOrderStage.OUT_FOR_DELIVERY, T0, T0);
        RunOrder failed = slot("d").advance(RunOrderStage.FAILED, T0, T0);
        RunOrder delivered = slot("e").advance(RunOrderStage.DELIVERED, T0.plusSeconds(100), T0);
        RunOrder later = slot("f").advance(RunOrderStage.DELIVERED, T0.plusSeconds(200), T0);
        RunOrder returned = slot("g").advance(RunOrderStage.RETURNED, T0, T0);
        RunOrder gone = slot("h").cancel(T0);
        RunProgress p = RunProgress.of(List.of(pending, collected, out, failed, delivered, later, returned, gone));
        assertThat(p.orders()).isEqualTo(7);
        assertThat(p.pending()).isEqualTo(1);
        assertThat(p.collected()).isEqualTo(1);
        assertThat(p.outForDelivery()).isEqualTo(1);
        assertThat(p.failed()).isEqualTo(1);
        assertThat(p.delivered()).isEqualTo(2);
        assertThat(p.returned()).isEqualTo(1);
        assertThat(p.lastDeliveredAt()).isEqualTo(T0.plusSeconds(200));
        assertThat(p.anyCollected()).isTrue();
        assertThat(p.allCollected()).isFalse();
        assertThat(p.allClosed()).isFalse();
    }

    @Test
    void aRunIsCollectedWhenNoOrderIsPendingAndClosedWhenEveryOrderIsDeliveredOrBack() {
        RunOrder a = slot("a").advance(RunOrderStage.OUT_FOR_DELIVERY, T0, T0);
        RunOrder b = slot("b").advance(RunOrderStage.FAILED, T0, T0);
        RunProgress sweepDone = RunProgress.of(List.of(a, b));
        assertThat(sweepDone.allCollected()).isTrue();
        assertThat(sweepDone.allClosed()).isFalse();
        RunProgress over = RunProgress.of(List.of(a.advance(RunOrderStage.DELIVERED, T0, T0),
                b.advance(RunOrderStage.RETURNED, T0, T0)));
        assertThat(over.allClosed()).isTrue();
        RunProgress none = RunProgress.of(List.of());
        assertThat(none.allClosed()).isFalse();
        assertThat(none.allCollected()).isFalse();
        assertThat(none.anyCollected()).isFalse();
    }

    @Test
    void sellerPickupIsTheDayBeforeButAfterPackingAndBeforeTheVan() {
        Instant windowStart = Instant.parse("2026-10-24T14:00:00Z");
        Duration day = Duration.ofHours(24);
        // ordering closed days ago: the wanted time (24 h before) wins
        assertThat(SellerPickupSchedule.at(Instant.parse("2026-10-20T01:00:00Z"), windowStart, day))
                .isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        // ordering closed 3 hours before the wanted time: still the wanted time (sellers had time to pack)
        assertThat(SellerPickupSchedule.at(Instant.parse("2026-10-23T11:00:00Z"), windowStart, day))
                .isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        // ordering closed 30 minutes before the wanted time: sellers get two hours to pack first
        assertThat(SellerPickupSchedule.at(Instant.parse("2026-10-23T13:30:00Z"), windowStart, day))
                .isEqualTo(Instant.parse("2026-10-23T15:30:00Z"));
        // a lead of a few minutes never puts the van behind the first drop
        assertThat(SellerPickupSchedule.at(Instant.parse("2026-10-20T01:00:00Z"), windowStart, Duration.ofMinutes(5)))
                .isEqualTo(Instant.parse("2026-10-24T13:00:00Z"));
        // ordering closed just before the window: the pickup is the cutoff itself (nothing earlier is possible)
        Instant cutoff = windowStart.minusSeconds(60);
        assertThat(SellerPickupSchedule.at(cutoff, windowStart, day)).isEqualTo(cutoff);
    }
}
