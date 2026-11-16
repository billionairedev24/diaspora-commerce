package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The transition table per fulfilment mode (docs/engineering/delivery-fulfilment.md "Who moves what"), one assertion
 * per row: what an administrator may do for each mode, what the delivery steps allow, and what an override may do.
 */
class ShipmentModeMatrixTest {

    private static final ShipmentTransitionPolicy POLICY = new DefaultShipmentTransitionPolicy();

    private static Shipment fulfilledBySeller(ShipmentStatus status) {
        String id = UUID.randomUUID().toString();
        LocalDelivery local = new LocalDelivery("loc-1", 29.7, -95.3, 4.0, 5.0, "ROAD_OSRM", 599, 250, 1, 12.0);
        return new Shipment(id, UUID.randomUUID().toString(), 1, "org_seller", "Mama Put Pantry",
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.AFROTRANSACT_DELIVERY, "local", null,
                DeliveryPreferences.defaults(), false, false, null, null, status, 599, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), null, null, null, null, Instant.now(),
                List.of(Shipments.line(1, 1250, 2, 103)), 0, local, null);
    }

    private static Shipment sellerShips(ShipmentStatus status) {
        return Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.SELLER_SHIPS, status);
    }

    private static Shipment pickup(ShipmentStatus status) {
        return Shipments.of(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, status);
    }

    private static Set<ShipmentStatus> next(Shipment s, ActorKind actor) {
        return POLICY.nextFor(s, actor);
    }

    private static Set<ShipmentStatus> set(ShipmentStatus... statuses) {
        return statuses.length == 0 ? EnumSet.noneOf(ShipmentStatus.class) : EnumSet.of(statuses[0], statuses);
    }

    // ------------------------------------------------------------- Fulfilled by AfroTransact (delivery steps)

    @Test
    void deliveryStepsFollowTheOrderOfTheJobAndNothingIsSkipped() {
        assertThat(next(fulfilledBySeller(ShipmentStatus.READY), ActorKind.DELIVERY_STAFF))
                .isEqualTo(set(ShipmentStatus.PICKED_UP));
        assertThat(next(fulfilledBySeller(ShipmentStatus.PICKED_UP), ActorKind.DELIVERY_STAFF)).isEqualTo(
                set(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_FAILED));
        assertThat(next(fulfilledBySeller(ShipmentStatus.IN_TRANSIT), ActorKind.DELIVERY_STAFF)).isEqualTo(
                set(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_FAILED));
        assertThat(next(fulfilledBySeller(ShipmentStatus.OUT_FOR_DELIVERY), ActorKind.DELIVERY_STAFF))
                .isEqualTo(set(ShipmentStatus.DELIVERED, ShipmentStatus.DELIVERY_FAILED));
        assertThat(next(fulfilledBySeller(ShipmentStatus.DELIVERY_FAILED), ActorKind.DELIVERY_STAFF))
                .isEqualTo(set(ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.RETURNED));
        assertThat(next(fulfilledBySeller(ShipmentStatus.DELIVERED), ActorKind.DELIVERY_STAFF)).isEmpty();
        assertThat(next(fulfilledBySeller(ShipmentStatus.RETURNED), ActorKind.DELIVERY_STAFF)).isEmpty();
    }

    @Test
    void deliveryStaffCannotSkipOrGoBackwards() {
        assertThatThrownBy(() -> POLICY.decide(fulfilledBySeller(ShipmentStatus.READY), ShipmentStatus.DELIVERED,
                ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(fulfilledBySeller(ShipmentStatus.READY),
                ShipmentStatus.OUT_FOR_DELIVERY, ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(fulfilledBySeller(ShipmentStatus.OUT_FOR_DELIVERY),
                ShipmentStatus.PICKED_UP, ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(fulfilledBySeller(ShipmentStatus.PICKED_UP), ShipmentStatus.RETURNED,
                ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
    }

    @Test
    void theSellerPacksAndTheAdminDoesNotPackForThem() {
        assertThat(next(fulfilledBySeller(ShipmentStatus.PLACED), ActorKind.SELLER))
                .isEqualTo(set(ShipmentStatus.CONFIRMED));
        assertThat(next(fulfilledBySeller(ShipmentStatus.CONFIRMED), ActorKind.SELLER))
                .isEqualTo(set(ShipmentStatus.PACKED));
        assertThat(next(fulfilledBySeller(ShipmentStatus.PACKED), ActorKind.SELLER))
                .isEqualTo(set(ShipmentStatus.READY));
        assertThat(next(fulfilledBySeller(ShipmentStatus.READY), ActorKind.SELLER)).isEmpty();
        assertThat(next(fulfilledBySeller(ShipmentStatus.PACKED), ActorKind.PLATFORM_ADMIN))
                .doesNotContain(ShipmentStatus.READY, ShipmentStatus.PACKED);
    }

    // ------------------------------------------------------------- Seller pickup

    @Test
    void pickupOffersReadyThenCollectedThenNotCollected() {
        assertThat(next(pickup(ShipmentStatus.CONFIRMED), ActorKind.PLATFORM_ADMIN))
                .contains(ShipmentStatus.READY).doesNotContain(ShipmentStatus.PICKED_UP,
                        ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.IN_TRANSIT);
        assertThat(next(pickup(ShipmentStatus.PACKED), ActorKind.PLATFORM_ADMIN)).contains(ShipmentStatus.READY);
        assertThat(next(pickup(ShipmentStatus.READY), ActorKind.PLATFORM_ADMIN))
                .contains(ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED)
                .doesNotContain(ShipmentStatus.PICKED_UP, ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(next(pickup(ShipmentStatus.READY), ActorKind.SELLER)).contains(ShipmentStatus.DELIVERED);
        assertThat(next(pickup(ShipmentStatus.DELIVERED), ActorKind.PLATFORM_ADMIN)).isEqualTo(
                set(ShipmentStatus.RETURNED));
    }

    @Test
    void deliveryStepsNeverApplyToPickup() {
        assertThatThrownBy(() -> POLICY.decide(pickup(ShipmentStatus.READY), ShipmentStatus.PICKED_UP,
                ActorKind.DELIVERY_STAFF)).isInstanceOf(TransitionRefused.class);
    }

    // ------------------------------------------------------------- Seller ships

    @Test
    void theSellerMovesTheirOwnShipmentAndStaffCannotWithoutTheOverride() {
        assertThat(next(sellerShips(ShipmentStatus.READY), ActorKind.SELLER)).isEqualTo(set(ShipmentStatus.PICKED_UP));
        assertThat(next(sellerShips(ShipmentStatus.PICKED_UP), ActorKind.SELLER))
                .isEqualTo(set(ShipmentStatus.DELIVERED));
        assertThat(next(sellerShips(ShipmentStatus.READY), ActorKind.DELIVERY_STAFF)).isEmpty();
        assertThat(next(sellerShips(ShipmentStatus.READY), ActorKind.PLATFORM_ADMIN))
                .doesNotContain(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void anOverrideMovesASellerShippedShipmentForwardOnly() {
        assertThat(next(sellerShips(ShipmentStatus.PICKED_UP), ActorKind.ADMIN_OVERRIDE)).containsExactlyInAnyOrder(
                ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERY_FAILED,
                ShipmentStatus.DELIVERED, ShipmentStatus.RETURNED);
        assertThat(next(sellerShips(ShipmentStatus.READY), ActorKind.ADMIN_OVERRIDE))
                .contains(ShipmentStatus.PICKED_UP, ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED)
                .doesNotContain(ShipmentStatus.PACKED, ShipmentStatus.CONFIRMED);
        assertThat(next(sellerShips(ShipmentStatus.DELIVERED), ActorKind.ADMIN_OVERRIDE))
                .isEqualTo(set(ShipmentStatus.RETURNED));
        assertThat(next(sellerShips(ShipmentStatus.CANCELLED), ActorKind.ADMIN_OVERRIDE)).isEmpty();
        assertThatThrownBy(() -> POLICY.decide(sellerShips(ShipmentStatus.OUT_FOR_DELIVERY),
                ShipmentStatus.PICKED_UP, ActorKind.ADMIN_OVERRIDE)).isInstanceOf(TransitionRefused.class);
        assertThatThrownBy(() -> POLICY.decide(sellerShips(ShipmentStatus.PICKED_UP), ShipmentStatus.CANCELLED,
                ActorKind.ADMIN_OVERRIDE)).isInstanceOf(TransitionRefused.class);
    }

    @Test
    void anOverrideNeverAppliesToAfroTransactDeliveredOrPickupShipments() {
        assertThat(next(fulfilledBySeller(ShipmentStatus.READY), ActorKind.ADMIN_OVERRIDE)).isEmpty();
        assertThat(next(Shipments.of(FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.READY), ActorKind.ADMIN_OVERRIDE)).isEmpty();
        assertThat(next(pickup(ShipmentStatus.READY), ActorKind.ADMIN_OVERRIDE)).isEmpty();
    }
}
