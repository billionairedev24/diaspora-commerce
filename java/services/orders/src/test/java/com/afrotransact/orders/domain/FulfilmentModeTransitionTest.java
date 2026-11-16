package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The state machine per fulfilment mode. Only Seller pickup and Fulfilled by AfroTransact exist; seller self-shipping
 * is retired, so a legacy seller-shipped shipment is history: the seller cannot move it, delivery staff never see it.
 */
class FulfilmentModeTransitionTest {

    private static final ShipmentTransitionPolicy POLICY = new DefaultShipmentTransitionPolicy();

    private static Shipment legacySellerShipped(ShipmentStatus status) {
        return Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.SELLER_SHIPS, status);
    }

    private static Shipment sellerOptedIn(ShipmentStatus status) {
        String id = UUID.randomUUID().toString();
        LocalDelivery local = new LocalDelivery("loc-1", 29.7, -95.3, 4.0, 5.0, "ROAD_OSRM", 599, 250, 1, 12.0);
        return new Shipment(id, UUID.randomUUID().toString(), 1, "org_seller", "Mama Tee's Pantry",
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.AFROTRANSACT_DELIVERY, "local", null,
                DeliveryPreferences.defaults(), false, false, null, null, status, 599, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), null, null, null, null, Instant.now(),
                List.of(Shipments.line(1, 1250, 2, 103)), 0, local, null);
    }

    private static Shipment fba(ShipmentStatus status) {
        return Shipments.of(FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.AFROTRANSACT_DELIVERY, status);
    }

    @Test
    void modeIsDerivedFromWhatTheShipmentCarries() {
        assertThat(legacySellerShipped(ShipmentStatus.READY).fulfilmentMode()).isEqualTo(FulfilmentMode.LEGACY_SELLER_SHIPPED);
        assertThat(sellerOptedIn(ShipmentStatus.READY).fulfilmentMode())
                .isEqualTo(FulfilmentMode.AFROTRANSACT_DELIVERY);
        assertThat(fba(ShipmentStatus.READY).fulfilmentMode()).isEqualTo(FulfilmentMode.FULFILLED_BY_AFROTRANSACT);
        assertThat(Shipments.of(FulfilmentType.RETAIL_1P, DeliveryMethod.AFROTRANSACT_DELIVERY, ShipmentStatus.READY)
                .fulfilmentMode()).isEqualTo(FulfilmentMode.FULFILLED_BY_AFROTRANSACT);
        assertThat(Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, ShipmentStatus.READY)
                .fulfilmentMode()).isEqualTo(FulfilmentMode.PICKUP);
    }

    /** A package the old checkout mislabelled (AfroTransact Delivery, seller-packed, no seller-store snapshot). */
    @Test
    void mislabelledLegacySellerPackageIsLegacyHistory() {
        Shipment legacy = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.READY);
        assertThat(legacy.fulfilmentMode()).isEqualTo(FulfilmentMode.LEGACY_SELLER_SHIPPED);
        assertThat(legacy.fulfilmentMode().staffDelivers()).isFalse();
    }

    @Test
    void onlyAfroTransactDeliveredModesEnterTheStaffQueue() {
        assertThat(FulfilmentMode.LEGACY_SELLER_SHIPPED.staffDelivers()).isFalse();
        assertThat(FulfilmentMode.PICKUP.staffDelivers()).isFalse();
        assertThat(FulfilmentMode.PREORDER_RUN.staffDelivers()).isFalse();
        assertThat(FulfilmentMode.AFROTRANSACT_DELIVERY.staffDelivers()).isTrue();
        assertThat(FulfilmentMode.FULFILLED_BY_AFROTRANSACT.staffDelivers()).isTrue();
        assertThat(FulfilmentMode.LEGACY_SELLER_SHIPPED.deliveredByAfroTransact()).isFalse();
    }

    @Test
    void legacySellerShippedOffersTheSellerNothing() {
        for (ShipmentStatus status : ShipmentStatus.values()) {
            assertThat(POLICY.nextFor(legacySellerShipped(status), ActorKind.SELLER)).as(status.name()).isEmpty();
            assertThat(POLICY.nextFor(legacySellerShipped(status), ActorKind.DELIVERY_STAFF)).as(status.name())
                    .isEmpty();
        }
        assertThatThrownBy(() -> POLICY.decide(legacySellerShipped(ShipmentStatus.READY), ShipmentStatus.PICKED_UP,
                ActorKind.SELLER)).isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(legacySellerShipped(ShipmentStatus.READY),
                ShipmentStatus.OUT_FOR_DELIVERY, ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
    }

    @Test
    void deliveryStaffAreRefusedOnTheMislabelledLegacyPackageToo() {
        Shipment legacy = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.READY);
        assertThat(POLICY.nextFor(legacy, ActorKind.DELIVERY_STAFF)).isEmpty();
    }

    @Test
    void legacySellerShippedNeverTakesCourierEvents() {
        Shipment shipped = legacySellerShipped(ShipmentStatus.PICKED_UP);
        assertThatThrownBy(() -> POLICY.decide(shipped, ShipmentStatus.OUT_FOR_DELIVERY, ActorKind.COURIER))
                .isInstanceOf(TransitionRefused.class);
    }

    @Test
    void legacySellerShippedCannotGainTrackingAnymore() {
        assertThatThrownBy(() -> legacySellerShipped(ShipmentStatus.READY).withTracking("local", "T1"))
                .isInstanceOf(OrdersRuleViolation.class);
    }

    @Test
    void sellerOptedInShipmentIsPreparedBySellerThenRunByDeliveryStaff() {
        assertThat(POLICY.nextFor(sellerOptedIn(ShipmentStatus.PLACED), ActorKind.SELLER))
                .containsExactly(ShipmentStatus.CONFIRMED);
        // The seller can pack and mark ready but never ships it themselves: AfroTransact collects.
        assertThat(POLICY.nextFor(sellerOptedIn(ShipmentStatus.READY), ActorKind.SELLER)).isEmpty();
        assertThat(POLICY.nextFor(sellerOptedIn(ShipmentStatus.READY), ActorKind.DELIVERY_STAFF))
                .containsExactlyInAnyOrder(ShipmentStatus.PICKED_UP);
    }

    @Test
    void deliveryStaffWalkTheWholeJobAndMayReattemptAfterAFailure() {
        Shipment s = fba(ShipmentStatus.READY);
        assertThat(POLICY.nextFor(s, ActorKind.DELIVERY_STAFF)).containsExactly(ShipmentStatus.PICKED_UP);
        s = fba(ShipmentStatus.PICKED_UP);
        assertThat(POLICY.nextFor(s, ActorKind.DELIVERY_STAFF))
                .containsExactlyInAnyOrder(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY,
                        ShipmentStatus.DELIVERY_FAILED);
        s = fba(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(POLICY.nextFor(s, ActorKind.DELIVERY_STAFF))
                .containsExactlyInAnyOrder(ShipmentStatus.DELIVERED, ShipmentStatus.DELIVERY_FAILED);
        s = fba(ShipmentStatus.DELIVERY_FAILED);
        assertThat(POLICY.nextFor(s, ActorKind.DELIVERY_STAFF))
                .containsExactlyInAnyOrder(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.RETURNED);
    }

    @Test
    void deliveryStaffCannotSkipOutForDeliveryOrDeliverAnythingTwice() {
        assertThatThrownBy(() -> POLICY.decide(fba(ShipmentStatus.PICKED_UP), ShipmentStatus.DELIVERED,
                ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
        assertThat(POLICY.decide(fba(ShipmentStatus.DELIVERED), ShipmentStatus.DELIVERED, ActorKind.DELIVERY_STAFF)
                .kind()).isEqualTo(TransitionDecision.Kind.UNCHANGED);
        Set<ShipmentStatus> next = POLICY.nextFor(fba(ShipmentStatus.DELIVERED), ActorKind.DELIVERY_STAFF);
        assertThat(next).isEmpty();
    }

    @Test
    void deliveryStaffNeverCancelOrReturn() {
        for (ShipmentStatus to : List.of(ShipmentStatus.CANCELLED, ShipmentStatus.RETURNED, ShipmentStatus.PACKED)) {
            assertThatThrownBy(() -> POLICY.decide(fba(ShipmentStatus.OUT_FOR_DELIVERY), to,
                    ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
        }
    }

    @Test
    void storePickupIsUnchanged() {
        Shipment ready = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, ShipmentStatus.READY);
        assertThat(POLICY.nextFor(ready, ActorKind.SELLER)).containsExactly(ShipmentStatus.DELIVERED);
        assertThat(POLICY.nextFor(ready, ActorKind.DELIVERY_STAFF)).isEmpty();
    }

    @Test
    void deliveryStaffDeliveredNeedsNoReasonBecauseTheDeskRequiresProof() {
        Shipment out = fba(ShipmentStatus.OUT_FOR_DELIVERY);
        TransitionResult r = out.transition(ShipmentStatus.DELIVERED,
                new TransitionActor(ActorKind.DELIVERY_STAFF, "staff-1", null), null, TransitionSource.API, "e1",
                Instant.now(), null, POLICY);
        assertThat(r.applied()).isTrue();
        assertThat(r.shipment().deliveredAt()).isNotNull();
    }

    @Test
    void aFailedDeliveryNeedsAReason() {
        Shipment out = fba(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThatThrownBy(() -> out.transition(ShipmentStatus.DELIVERY_FAILED,
                new TransitionActor(ActorKind.DELIVERY_STAFF, "staff-1", null), " ", TransitionSource.API, "e2",
                Instant.now(), null, POLICY)).isInstanceOf(OrdersRuleViolation.class);
    }
}
