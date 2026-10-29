package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ShipmentTest {

    private static final ShipmentTransitionPolicy POLICY = new DefaultShipmentTransitionPolicy();
    private static final TransitionActor UPS = new TransitionActor(ActorKind.CARRIER, "ups", null);
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static TransitionResult scan(Shipment s, ShipmentStatus to, Instant at) {
        return s.transition(to, UPS, "scan", TransitionSource.CARRIER, "evt-" + to + at, Instant.now(), at, POLICY);
    }

    @Test
    void appliesARecordedTransitionWithActorSourceAndReason() {
        Shipment s = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, ShipmentStatus.PLACED);
        TransitionResult r = s.transition(ShipmentStatus.CONFIRMED,
                new TransitionActor(ActorKind.SELLER, "user-1", "org_seller"), "on it", TransitionSource.API, "k-1",
                T0, null, POLICY);
        assertThat(r.applied()).isTrue();
        assertThat(r.shipment().status()).isEqualTo(ShipmentStatus.CONFIRMED);
        assertThat(r.change()).satisfies(c -> {
            assertThat(c.from()).isEqualTo(ShipmentStatus.PLACED);
            assertThat(c.to()).isEqualTo(ShipmentStatus.CONFIRMED);
            assertThat(c.actorKind()).isEqualTo(ActorKind.SELLER);
            assertThat(c.actorId()).isEqualTo("user-1");
            assertThat(c.actorOrg()).isEqualTo("org_seller");
            assertThat(c.reason()).isEqualTo("on it");
            assertThat(c.source()).isEqualTo(TransitionSource.API);
            assertThat(c.eventId()).isEqualTo("k-1");
            assertThat(c.occurredAt()).isEqualTo(T0);
        });
    }

    @Test
    void outOfOrderCarrierEventsNeverMoveTheShipmentBackwards() {
        Shipment s = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, ShipmentStatus.READY);
        s = scan(s, ShipmentStatus.IN_TRANSIT, T0.plusSeconds(60)).shipment();
        TransitionResult late = scan(s, ShipmentStatus.PICKED_UP, T0.plusSeconds(120));
        assertThat(late.kind()).isEqualTo(TransitionDecision.Kind.IGNORE);
        assertThat(late.shipment().status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        s = scan(s, ShipmentStatus.DELIVERED, T0.plusSeconds(600)).shipment();
        assertThat(s.deliveredAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(scan(s, ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(900)).kind())
                .isEqualTo(TransitionDecision.Kind.IGNORE);
    }

    @Test
    void staleScansOlderThanTheLastAppliedOneAreIgnored() {
        Shipment s = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, ShipmentStatus.READY);
        s = scan(s, ShipmentStatus.DELIVERY_FAILED, T0.plusSeconds(300)).shipment();
        TransitionResult stale = scan(s, ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(200));
        assertThat(stale.kind()).isEqualTo(TransitionDecision.Kind.IGNORE);
        assertThat(stale.note()).contains("stale");
        TransitionResult reattempt = scan(s, ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(3600));
        assertThat(reattempt.applied()).isTrue();
    }

    @Test
    void cancellationsAndFailuresNeedAReason() {
        Shipment s = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, ShipmentStatus.PLACED);
        assertThatThrownBy(() -> s.transition(ShipmentStatus.CANCELLED,
                new TransitionActor(ActorKind.CUSTOMER, "c-1", null), " ", TransitionSource.API, "k", T0, null, POLICY))
                .isInstanceOf(OrdersRuleViolation.class).hasMessageContaining("reason");
    }

    @Test
    void staffConfirmingADriverDeliveryByHandSayWhy() {
        Shipment s = Shipments.of(FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.OUT_FOR_DELIVERY);
        TransitionActor admin = new TransitionActor(ActorKind.PLATFORM_ADMIN, "admin-1", null);
        assertThatThrownBy(() -> s.transition(ShipmentStatus.DELIVERED, admin, " ", TransitionSource.API, "k", T0,
                null, POLICY)).isInstanceOf(OrdersRuleViolation.class).hasMessageContaining("proof");
        TransitionResult r = s.transition(ShipmentStatus.DELIVERED, admin, "Driver's phone died at the door",
                TransitionSource.API, "k2", T0, null, POLICY);
        assertThat(r.applied()).isTrue();
        assertThat(r.change().reason()).isEqualTo("Driver's phone died at the door");
    }

    @Test
    void sameStatusIsIdempotent() {
        Shipment s = Shipments.of(FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER, ShipmentStatus.PACKED);
        TransitionResult r = s.transition(ShipmentStatus.PACKED,
                new TransitionActor(ActorKind.FULFILMENT_OPS, "ops", null), null, TransitionSource.API, "k", T0, null,
                POLICY);
        assertThat(r.kind()).isEqualTo(TransitionDecision.Kind.UNCHANGED);
        assertThat(r.change()).isNull();
    }

    @Test
    void trackingOnlyForCarrierShipments() {
        Shipment courier = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.PACKED);
        assertThatThrownBy(() -> courier.withTracking("ups", "1Z")).isInstanceOf(OrdersRuleViolation.class);
        Shipment carrier = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, ShipmentStatus.PACKED);
        assertThat(carrier.withTracking("ups", "1Z999").trackingNumber()).isEqualTo("1Z999");
    }

    @Test
    void handedOverMeansPickedUpOrLaterButNotCancelled() {
        assertThat(ShipmentStatus.READY.handedOver()).isFalse();
        assertThat(ShipmentStatus.PICKED_UP.handedOver()).isTrue();
        assertThat(ShipmentStatus.DELIVERED.handedOver()).isTrue();
        assertThat(ShipmentStatus.CANCELLED.handedOver()).isFalse();
        assertThat(ShipmentStatus.PICKED_UP.wire()).isEqualTo("picked_up");
    }
}
