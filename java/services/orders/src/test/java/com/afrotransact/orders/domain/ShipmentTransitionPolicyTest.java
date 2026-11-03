package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The full actor × fulfilment-type × from × to matrix of architecture §17c "Who can do what". The expected edges are
 * written out independently of the policy implementation.
 */
class ShipmentTransitionPolicyTest {

    private static final ShipmentTransitionPolicy POLICY = new DefaultShipmentTransitionPolicy();

    private static final ShipmentStatus PENDING = ShipmentStatus.PENDING_PAYMENT;
    private static final ShipmentStatus PLACED = ShipmentStatus.PLACED;
    private static final ShipmentStatus CONFIRMED = ShipmentStatus.CONFIRMED;
    private static final ShipmentStatus PACKED = ShipmentStatus.PACKED;
    private static final ShipmentStatus READY = ShipmentStatus.READY;
    private static final ShipmentStatus PICKED_UP = ShipmentStatus.PICKED_UP;
    private static final ShipmentStatus IN_TRANSIT = ShipmentStatus.IN_TRANSIT;
    private static final ShipmentStatus OUT = ShipmentStatus.OUT_FOR_DELIVERY;
    private static final ShipmentStatus FAILED = ShipmentStatus.DELIVERY_FAILED;
    private static final ShipmentStatus DELIVERED = ShipmentStatus.DELIVERED;
    private static final ShipmentStatus RETURNED = ShipmentStatus.RETURNED;
    private static final ShipmentStatus CANCELLED = ShipmentStatus.CANCELLED;

    /** Expected edges: target → allowed from-statuses, for the step-owning actors (carrier delivery method). */
    private static Map<ShipmentStatus, Set<ShipmentStatus>> expected(ActorKind actor, FulfilmentType type) {
        Map<ShipmentStatus, Set<ShipmentStatus>> m = new EnumMap<>(ShipmentStatus.class);
        boolean seller = type == FulfilmentType.SELLER_FULFILLED;
        switch (actor) {
            case CUSTOMER -> m.put(CANCELLED, EnumSet.of(PLACED, CONFIRMED));
            case SYSTEM -> {
                m.put(PLACED, EnumSet.of(PENDING));
                m.put(CANCELLED, EnumSet.of(PENDING));
            }
            case SELLER -> {
                if (seller) {
                    m.put(CONFIRMED, EnumSet.of(PLACED));
                    m.put(PACKED, EnumSet.of(CONFIRMED));
                    m.put(READY, EnumSet.of(PACKED));
                    // Carrier parcels (the matrix uses the carrier method): shipped once the label exists.
                    m.put(PICKED_UP, EnumSet.of(READY));
                    // ... and confirmed delivered by the seller when the carrier's tracking has not reported it.
                    m.put(DELIVERED, EnumSet.of(PICKED_UP, IN_TRANSIT, OUT));
                }
            }
            case PLATFORM_ADMIN, FULFILMENT_OPS -> {
                if (seller && actor == ActorKind.FULFILMENT_OPS) {
                    break;
                }
                m.put(DELIVERED, EnumSet.of(PICKED_UP, IN_TRANSIT, OUT, FAILED));
                m.put(FAILED, EnumSet.of(PICKED_UP, IN_TRANSIT, OUT));
                m.put(RETURNED, EnumSet.of(IN_TRANSIT, OUT, FAILED, DELIVERED));
                m.put(CANCELLED, EnumSet.of(PLACED, CONFIRMED, PACKED, READY));
                if (!seller) {
                    m.put(CONFIRMED, EnumSet.of(PLACED));
                    m.put(PACKED, EnumSet.of(CONFIRMED));
                    m.put(READY, EnumSet.of(PACKED));
                    m.put(PICKED_UP, EnumSet.of(READY));
                    m.put(IN_TRANSIT, EnumSet.of(PICKED_UP, FAILED));
                    m.put(OUT, EnumSet.of(PICKED_UP, IN_TRANSIT, FAILED));
                }
            }
            default -> throw new IllegalArgumentException(actor.name());
        }
        return m;
    }

    static Stream<Arguments> matrix() {
        List<Arguments> out = new ArrayList<>();
        for (ActorKind actor : List.of(ActorKind.CUSTOMER, ActorKind.SYSTEM, ActorKind.SELLER,
                ActorKind.PLATFORM_ADMIN, ActorKind.FULFILMENT_OPS)) {
            for (FulfilmentType type : FulfilmentType.values()) {
                for (ShipmentStatus from : ShipmentStatus.values()) {
                    for (ShipmentStatus to : ShipmentStatus.values()) {
                        out.add(Arguments.of(actor, type, from, to));
                    }
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} on {1}: {2} -> {3}")
    @MethodSource("matrix")
    void everyActorFulfilmentAndStatusCombination(ActorKind actor, FulfilmentType type, ShipmentStatus from,
                                                  ShipmentStatus to) {
        Map<ShipmentStatus, Set<ShipmentStatus>> rules = expected(actor, type);
        Shipment s = Shipments.of(type, DeliveryMethod.CARRIER, from);
        if (!rules.containsKey(to)) {
            assertThatThrownBy(() -> POLICY.decide(s, to, actor)).isInstanceOf(TransitionRefused.class)
                    .satisfies(e -> assertThat(((TransitionRefused) e).forbidden()).isTrue());
        } else if (from == to) {
            assertThat(POLICY.decide(s, to, actor).kind()).isEqualTo(TransitionDecision.Kind.UNCHANGED);
        } else if (rules.get(to).contains(from)) {
            assertThat(POLICY.decide(s, to, actor).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        } else {
            assertThatThrownBy(() -> POLICY.decide(s, to, actor)).isInstanceOf(TransitionRefused.class)
                    .satisfies(e -> assertThat(((TransitionRefused) e).forbidden()).isFalse());
        }
    }

    // ------------------------------------------------------------------ the rules in words

    @Test
    void sellerMarksACarrierParcelShippedOnlyWithALabel() {
        Shipment ready = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, READY);
        assertThat(POLICY.decide(ready, PICKED_UP, ActorKind.SELLER).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        assertThat(POLICY.nextFor(ready, ActorKind.SELLER)).containsExactly(PICKED_UP);
        Shipment unlabelled = ready.withTracking(null, null);
        assertThatThrownBy(() -> POLICY.decide(unlabelled, PICKED_UP, ActorKind.SELLER))
                .isInstanceOf(TransitionRefused.class).hasMessageContaining("shipping label");
        Shipment pickup = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, READY);
        assertThatThrownBy(() -> POLICY.decide(pickup, PICKED_UP, ActorKind.SELLER))
                .isInstanceOf(TransitionRefused.class);
    }

    @Test
    void onlyTheSellerConfirmsPacksAndMarksReadyASellerFulfilledShipment() {
        Shipment placed = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, PLACED);
        assertThat(POLICY.decide(placed, CONFIRMED, ActorKind.SELLER).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        for (ActorKind other : List.of(ActorKind.PLATFORM_ADMIN, ActorKind.FULFILMENT_OPS, ActorKind.CUSTOMER)) {
            for (ShipmentStatus step : List.of(CONFIRMED, PACKED, READY)) {
                assertThatThrownBy(() -> POLICY.decide(placed, step, other))
                        .isInstanceOf(TransitionRefused.class).hasMessageContaining("may not set");
            }
        }
    }

    @Test
    void platformAdminsNeverMarkASellerFulfilledShipmentPacked() {
        Shipment confirmed = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, CONFIRMED);
        assertThatThrownBy(() -> POLICY.decide(confirmed, PACKED, ActorKind.PLATFORM_ADMIN))
                .isInstanceOf(TransitionRefused.class)
                .satisfies(e -> assertThat(((TransitionRefused) e).code()).isEqualTo(TransitionRefused.FORBIDDEN));
    }

    @Test
    void platformAdminsConfirmDeliveryRecordFailuresProcessReturnsAndCancelSellerShipments() {
        FulfilmentType seller = FulfilmentType.SELLER_FULFILLED;
        assertThat(POLICY.decide(Shipments.of(seller, DeliveryMethod.CARRIER, IN_TRANSIT), DELIVERED,
                ActorKind.PLATFORM_ADMIN).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        assertThat(POLICY.decide(Shipments.of(seller, DeliveryMethod.CARRIER, OUT), FAILED,
                ActorKind.PLATFORM_ADMIN).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        assertThat(POLICY.decide(Shipments.of(seller, DeliveryMethod.CARRIER, DELIVERED), RETURNED,
                ActorKind.PLATFORM_ADMIN).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        assertThat(POLICY.decide(Shipments.of(seller, DeliveryMethod.CARRIER, READY), CANCELLED,
                ActorKind.PLATFORM_ADMIN).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
    }

    @Test
    void sellersConfirmDeliveryOfCarrierParcelsOnceShipped() {
        for (ShipmentStatus on : List.of(PICKED_UP, IN_TRANSIT, OUT)) {
            Shipment shipped = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, on);
            assertThat(POLICY.decide(shipped, DELIVERED, ActorKind.SELLER).kind())
                    .isEqualTo(TransitionDecision.Kind.ALLOW);
            assertThat(POLICY.nextFor(shipped, ActorKind.SELLER)).containsExactly(DELIVERED);
        }
    }

    @Test
    void sellersCannotMarkDeliveredBeforeTheParcelIsShipped() {
        Shipment ready = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, READY);
        assertThatThrownBy(() -> POLICY.decide(ready, DELIVERED, ActorKind.SELLER))
                .isInstanceOf(TransitionRefused.class);
    }

    @Test
    void sellersCannotMarkDeliveredOnCourierDeliveries() {
        Shipment onTheWay = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                OUT);
        assertThatThrownBy(() -> POLICY.decide(onTheWay, DELIVERED, ActorKind.SELLER))
                .isInstanceOf(TransitionRefused.class)
                .satisfies(e -> assertThat(((TransitionRefused) e).forbidden()).isTrue());
    }

    @Test
    void sellersHandPickupOrdersOverAtTheirShop() {
        Shipment ready = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, READY);
        assertThat(POLICY.decide(ready, DELIVERED, ActorKind.SELLER).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        assertThat(POLICY.decide(ready, DELIVERED, ActorKind.PLATFORM_ADMIN).kind())
                .isEqualTo(TransitionDecision.Kind.ALLOW);
    }

    @Test
    void afrotransactFulfilledShipmentsBelongToAdminsAndFulfilmentOpsAtEveryStep() {
        for (FulfilmentType type : List.of(FulfilmentType.FBA_AFROTRANSACT, FulfilmentType.RETAIL_1P)) {
            for (ActorKind owner : List.of(ActorKind.PLATFORM_ADMIN, ActorKind.FULFILMENT_OPS)) {
                ShipmentStatus[] path = {PLACED, CONFIRMED, PACKED, READY, PICKED_UP, IN_TRANSIT, OUT, DELIVERED};
                for (int i = 0; i + 1 < path.length; i++) {
                    assertThat(POLICY.decide(Shipments.of(type, DeliveryMethod.CARRIER, path[i]), path[i + 1], owner)
                            .kind()).as("%s %s %s->%s", owner, type, path[i], path[i + 1])
                            .isEqualTo(TransitionDecision.Kind.ALLOW);
                }
            }
            assertThatThrownBy(() -> POLICY.decide(Shipments.of(type, DeliveryMethod.CARRIER, PLACED), CONFIRMED,
                    ActorKind.SELLER)).isInstanceOf(TransitionRefused.class);
        }
    }

    @Test
    void customersCancelUntilPacked() {
        for (FulfilmentType type : FulfilmentType.values()) {
            assertThat(POLICY.decide(Shipments.of(type, DeliveryMethod.CARRIER, PLACED), CANCELLED,
                    ActorKind.CUSTOMER).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
            assertThat(POLICY.decide(Shipments.of(type, DeliveryMethod.CARRIER, CONFIRMED), CANCELLED,
                    ActorKind.CUSTOMER).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
            for (ShipmentStatus late : List.of(PACKED, READY, PICKED_UP, IN_TRANSIT, DELIVERED)) {
                assertThatThrownBy(() -> POLICY.decide(Shipments.of(type, DeliveryMethod.CARRIER, late), CANCELLED,
                        ActorKind.CUSTOMER)).isInstanceOf(TransitionRefused.class)
                        .satisfies(e -> assertThat(((TransitionRefused) e).code())
                                .isEqualTo(TransitionRefused.CANCEL_WINDOW_CLOSED));
            }
        }
    }

    // ------------------------------------------------------------------ carriers and couriers

    @Test
    void carriersAndCouriersOnlyMoveTransitSteps() {
        for (FulfilmentType type : FulfilmentType.values()) {
            Shipment carrier = Shipments.of(type, DeliveryMethod.CARRIER, READY);
            Shipment courier = Shipments.of(type, DeliveryMethod.AFROTRANSACT_DELIVERY, READY);
            for (ShipmentStatus to : List.of(PICKED_UP, IN_TRANSIT, OUT, DELIVERED, FAILED, RETURNED)) {
                assertThat(POLICY.decide(carrier, to, ActorKind.CARRIER).kind()).isEqualTo(
                        TransitionDecision.Kind.ALLOW);
                assertThat(POLICY.decide(courier, to, ActorKind.COURIER).kind()).isEqualTo(
                        TransitionDecision.Kind.ALLOW);
            }
            for (ShipmentStatus to : List.of(CONFIRMED, PACKED, READY, CANCELLED, PLACED)) {
                assertThatThrownBy(() -> POLICY.decide(carrier, to, ActorKind.CARRIER))
                        .isInstanceOf(TransitionRefused.class);
                assertThatThrownBy(() -> POLICY.decide(courier, to, ActorKind.COURIER))
                        .isInstanceOf(TransitionRefused.class);
            }
        }
    }

    @Test
    void carrierEventsOnlyApplyToCarrierShipmentsAndCourierEventsToAfroTransactDelivery() {
        Shipment courier = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY, READY);
        Shipment carrier = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, READY);
        assertThatThrownBy(() -> POLICY.decide(courier, PICKED_UP, ActorKind.CARRIER))
                .isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(carrier, PICKED_UP, ActorKind.COURIER))
                .isInstanceOf(TransitionRefused.class);
    }

    @Test
    void aRunsDriverMovesPreorderPickupShipmentsThroughTheTransitStepsButNoOtherPickupShipments() {
        Shipment pickupDrop = Shipments.of(FulfilmentType.PREORDER, DeliveryMethod.PICKUP, CONFIRMED);
        for (ShipmentStatus to : List.of(PICKED_UP, OUT, DELIVERED)) {
            assertThat(POLICY.decide(pickupDrop, to, ActorKind.COURIER).kind())
                    .isEqualTo(TransitionDecision.Kind.ALLOW);
        }
        // the driver never moves the prepare steps, and carriers have nothing to do with a pickup point
        assertThatThrownBy(() -> POLICY.decide(pickupDrop, PACKED, ActorKind.COURIER))
                .isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(pickupDrop, PICKED_UP, ActorKind.CARRIER))
                .isInstanceOf(TransitionRefused.class);
        // a seller's own pickup order (not a pre-order run) is still handed over by the seller, not a courier
        Shipment sellerPickup = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, READY);
        assertThatThrownBy(() -> POLICY.decide(sellerPickup, DELIVERED, ActorKind.COURIER))
                .isInstanceOf(TransitionRefused.class);
        // forward only, and a delivered package stays delivered
        Shipment delivered = Shipments.of(FulfilmentType.PREORDER, DeliveryMethod.PICKUP, DELIVERED);
        assertThat(POLICY.decide(delivered, OUT, ActorKind.COURIER).kind()).isEqualTo(TransitionDecision.Kind.IGNORE);
        Shipment placed = Shipments.of(FulfilmentType.PREORDER, DeliveryMethod.PICKUP, PLACED);
        assertThatThrownBy(() -> POLICY.decide(placed, PICKED_UP, ActorKind.COURIER))
                .isInstanceOf(TransitionRefused.class);
    }

    @Test
    void transitEventsNeverMoveBackwardsButFailedDeliveriesCanBeReattempted() {
        Shipment out = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, OUT);
        assertThat(POLICY.decide(out, IN_TRANSIT, ActorKind.CARRIER).kind()).isEqualTo(TransitionDecision.Kind.IGNORE);
        assertThat(POLICY.decide(out, PICKED_UP, ActorKind.CARRIER).kind()).isEqualTo(TransitionDecision.Kind.IGNORE);
        Shipment delivered = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, DELIVERED);
        assertThat(POLICY.decide(delivered, IN_TRANSIT, ActorKind.CARRIER).kind())
                .isEqualTo(TransitionDecision.Kind.IGNORE);
        Shipment failed = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, FAILED);
        assertThat(POLICY.decide(failed, OUT, ActorKind.CARRIER).kind()).isEqualTo(TransitionDecision.Kind.ALLOW);
        Shipment placed = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, PLACED);
        assertThatThrownBy(() -> POLICY.decide(placed, IN_TRANSIT, ActorKind.CARRIER))
                .isInstanceOf(TransitionRefused.class);
    }

    @Test
    void nextActionsReflectTheRules() {
        Shipment placed = Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, PLACED);
        assertThat(POLICY.nextFor(placed, ActorKind.SELLER)).containsExactly(CONFIRMED);
        assertThat(POLICY.nextFor(placed, ActorKind.PLATFORM_ADMIN)).containsExactly(CANCELLED);
        assertThat(POLICY.nextFor(placed, ActorKind.CUSTOMER)).containsExactly(CANCELLED);
    }
}
