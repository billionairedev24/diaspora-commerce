package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.orders.domain.DefaultShipmentTransitionPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.TransitionRefused;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.ShipmentFeed;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.orders.port.StaffDirectory.StaffPerson;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Delivery desk: permissions, the per-mode queue rule, proof rules, audit, idempotency and optimistic locking. */
class DeliveryDeskServiceTest {

    private static final String PHOTO = "3f2b8c1e-9d4a-4b6e-8f10-2a7c5d9e1b34";
    private static final String PHOTO_2 = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    private final TenantFixture tenants = new TenantFixture();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final InMemoryDeliveryDesk store = new InMemoryDeliveryDesk();
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final AtomicReference<Order> stored = new AtomicReference<>();
    private DeliveryDeskServiceImpl desk;
    private Shipment shipment;
    private String shipmentId;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC);
        OrdersAccess access = new OrdersAccess(tenants.permissions(), tenants.access(), tenants.resolver());
        DefaultShipmentTransitionPolicy policy = new DefaultShipmentTransitionPolicy();
        ShipmentTransitions transitions = new ShipmentTransitions(orders, mock(FollowUpRepository.class), policy,
                events, mock(ShipmentFeed.class), clock);
        desk = new DeliveryDeskServiceImpl(access, orders, store, transitions, policy, staff, events, clock);
        place(FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.AFROTRANSACT_DELIVERY, null, ShipmentStatus.READY);
        when(staff.people(any())).thenReturn(List.of(new StaffPerson("ops-user", "Chidi Nwosu")));
        when(staff.withPermission("delivery:manage")).thenReturn(List.of(new StaffPerson("ops-user", "Chidi Nwosu"),
                new StaffPerson("courier-2", "Segun Lawal")));
        tenants.as(TenantFixture.staff("delivery:manage", "delivery:read"));
    }

    private void place(FulfilmentType type, DeliveryMethod method, LocalDelivery local, ShipmentStatus status) {
        String orderId = UUID.randomUUID().toString();
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-1", "P-1", "Jollof spice mix", null, 2,
                1500, 124, 0, "groceries", false, "US-TX");
        shipment = new Shipment(UUID.randomUUID().toString(), orderId, 1, "org-seller", "Mama Tee's Pantry", type,
                type == FulfilmentType.SELLER_FULFILLED ? null : "HOU-FC", method, "local",
                method == DeliveryMethod.PICKUP ? "PICKUP-1" : null, DeliveryPreferences.defaults(), false, false, null, null, status, 599, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), null, null, null, null, Instant.now(), List.of(line), 0, local, null);
        shipmentId = shipment.id();
        Order order = new Order(orderId, "AT-2026-DELIV1", "cust-1", "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipment)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Houston", "TX", "77002", "US", "Gate code 4411"), UUID.randomUUID().toString(),
                "checkout-" + orderId, null, Instant.now(), Instant.now(), List.of(shipment), 0);
        stored.set(order);
        when(orders.orderIdOfShipment(shipmentId)).thenReturn(Optional.of(orderId));
        when(orders.lockById(orderId)).thenAnswer(i -> Optional.of(stored.get()));
        when(orders.findById(orderId)).thenAnswer(i -> Optional.of(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(0));
            return null;
        }).when(orders).update(any());
    }

    private static LocalDelivery sellerStore() {
        return new LocalDelivery("loc-1", 29.7, -95.3, 4.0, 5.0, "ROAD_OSRM", 599, 250, 1, 12.0);
    }

    private static DeliveryDeskService.Command key(String k) {
        return new DeliveryDeskService.Command(null, k, null);
    }

    private static DeliveryDeskService.ProofInput proof() {
        return new DeliveryDeskService.ProofInput(List.of(PHOTO), "Ada Obi", "Left with the neighbour", null, null);
    }

    private ShipmentStatus status() {
        return stored.get().shipment(shipmentId).status();
    }

    @Test
    void walksTheWholeJobWithProofAndAuditsEveryStep() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        DeliveryDeskService.Detail done = desk.delivered(shipmentId, proof(), key("k4"));

        assertThat(status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(done.proof().photoAssetIds()).containsExactly(PHOTO);
        assertThat(done.proof().recipientName()).isEqualTo("Ada Obi");
        assertThat(done.proof().recordedByName()).isEqualTo("Chidi Nwosu");
        assertThat(done.row().assigneeName()).isEqualTo("Chidi Nwosu");
        assertThat(store.audit(shipmentId)).extracting(l -> l.action())
                .containsExactly("TAKE", "PICKED_UP", "OUT_FOR_DELIVERY", "DELIVERED");
        assertThat(store.audit(shipmentId)).allMatch(l -> "Chidi Nwosu".equals(l.actorName()));
        verify(events).publish(eq(OrdersEvents.DELIVERY_PROOF_ATTACHED),
                eq(OrdersEvents.AGGREGATE), any(), any());
    }

    @Test
    void deliveredWithoutAPhotoIsRefusedAndChangesNothing() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        assertThatThrownBy(() -> desk.delivered(shipmentId,
                new DeliveryDeskService.ProofInput(List.of(), "Ada", null, null, null), key("k4")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-photo-required");
        assertThat(status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(store.proofs).isEmpty();
    }

    @Test
    void proofIsValidated() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        assertThatThrownBy(() -> desk.delivered(shipmentId,
                new DeliveryDeskService.ProofInput(List.of("not-an-asset"), null, null, null, null), key("a")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-photo-invalid");
        assertThatThrownBy(() -> desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO), "x".repeat(121), null, null, null), key("b")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-recipient-too-long");
        assertThatThrownBy(() -> desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO), null, "n".repeat(501), null, null), key("c")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-note-too-long");
        assertThatThrownBy(() -> desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO), null, null, 29.7, null), key("d")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-position-incomplete");
        assertThatThrownBy(() -> desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO), null, null, 91.0, 10.0), key("e")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-position-invalid");
        assertThatThrownBy(() -> desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO, PHOTO_2, PHOTO, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), UUID.randomUUID().toString()), null, null, null, null), key("f")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.proof-too-many-photos");
        assertThat(status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void coordinatesAreOptionalAndKeptWhenGiven() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        DeliveryDeskService.Detail d = desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO.toUpperCase(Locale.ROOT)), null, null, 29.76, -95.37), key("k4"));
        assertThat(d.proof().latitude()).isEqualTo(29.76);
        assertThat(d.proof().recipientName()).isNull();
        assertThat(d.proof().photoAssetIds()).containsExactly(PHOTO);
    }

    @Test
    void aStepWithNoJobTakesTheJobAutomaticallyAndAuditsTheTake() {
        DeliveryDeskService.Detail d = desk.pickedUp(shipmentId, new DeliveryDeskService.Command(null, "k1",
                "Collected from the shop, 2 boxes"));
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(d.row().assigneeName()).isEqualTo("Chidi Nwosu");
        assertThat(store.audit(shipmentId)).extracting(l -> l.action()).containsExactly("TAKE", "PICKED_UP");
        assertThat(store.audit(shipmentId).get(1).detail()).containsEntry("note", "Collected from the shop, 2 boxes");
    }

    @Test
    void anIllegalStepWithNoJobTakesNothing() {
        assertThatThrownBy(() -> desk.outForDelivery(shipmentId, key("k1")))
                .isInstanceOf(TransitionRefused.class);
        assertThat(status()).isEqualTo(ShipmentStatus.READY);
        assertThat(store.jobs).isEmpty();
    }

    @Test
    void inTransitIsOptionalAndNothingElseCanBeSkipped() {
        assertThatThrownBy(() -> desk.delivered(shipmentId, proof(), key("k0")))
                .isInstanceOf(TransitionRefused.class);
        desk.pickedUp(shipmentId, key("k1"));
        desk.inTransit(shipmentId, key("k2"));
        assertThat(status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        desk.outForDelivery(shipmentId, key("k3"));
        assertThat(status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(store.audit(shipmentId)).extracting(l -> l.action())
                .containsExactly("TAKE", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY");
    }

    @Test
    void aFailedDeliveryNeedsAReasonThenIsReattemptedOrReturned() {
        desk.pickedUp(shipmentId, key("k1"));
        desk.outForDelivery(shipmentId, key("k2"));
        assertThatThrownBy(() -> desk.failed(shipmentId, " ", key("k3"))).isInstanceOf(OrdersRuleViolation.class);
        desk.failed(shipmentId, "Nobody home, left a card", key("k4"));
        assertThat(status()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
        assertThatThrownBy(() -> desk.returned(shipmentId, "", key("k5"))).isInstanceOf(OrdersRuleViolation.class);
        desk.returned(shipmentId, "Customer asked for a refund", key("k6"));
        assertThat(status()).isEqualTo(ShipmentStatus.RETURNED);
        assertThat(store.audit(shipmentId).getLast().action()).isEqualTo("RETURNED");
    }

    @Test
    void returnedIsOnlyAfterAFailedAttempt() {
        desk.pickedUp(shipmentId, key("k1"));
        assertThatThrownBy(() -> desk.returned(shipmentId, "changed my mind", key("k2")))
                .isInstanceOf(TransitionRefused.class);
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void aCallerWhoSawAnOlderStatusIsToldNotOverruled() {
        desk.pickedUp(shipmentId, key("k1"));
        assertThatThrownBy(() -> desk.inTransit(shipmentId, new DeliveryDeskService.Command(null, "k2", null,
                "ready"))).isInstanceOf(OrdersStateConflict.class).extracting("code")
                .isEqualTo("orders.delivery-status-changed");
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void everyDeskStepIsRefusedWithoutDeliveryManage() {
        tenants.as(TenantFixture.staff("delivery:read", "orders:manage"));
        assertThatThrownBy(() -> desk.pickedUp(shipmentId, key("k1"))).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> desk.inTransit(shipmentId, key("k2"))).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> desk.returned(shipmentId, "x y z", key("k3")))
                .isInstanceOf(ForbiddenActionException.class);
        assertThat(status()).isEqualTo(ShipmentStatus.READY);
        assertThat(store.audit(shipmentId)).isEmpty();
    }

    @Test
    void theSameKeyTwiceChangesAndRecordsNothingMore() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.take(shipmentId, key("k1"));
        assertThat(store.audit(shipmentId)).hasSize(2);
        assertThat(store.jobs.get(shipmentId).version()).isEqualTo(2);
    }

    @Test
    void theSameStepWithANewKeyIsHarmless() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.pickedUp(shipmentId, key("k9"));
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(store.audit(shipmentId)).hasSize(2);
    }

    @Test
    void aDeliveredShipmentKeepsItsFirstProof() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        desk.delivered(shipmentId, proof(), key("k4"));
        desk.delivered(shipmentId, new DeliveryDeskService.ProofInput(List.of(PHOTO_2), "Someone else", null, null,
                null), key("k5"));
        assertThat(store.proofs.get(shipmentId).photoAssetIds()).containsExactly(PHOTO);
        assertThat(store.proofs.get(shipmentId).recipientName()).isEqualTo("Ada Obi");
    }

    @Test
    void staleVersionIsRefused() {
        desk.take(shipmentId, key("k1"));
        assertThatThrownBy(() -> desk.pickedUp(shipmentId, new DeliveryDeskService.Command(0L, "k2", null)))
                .isInstanceOf(OrdersStateConflict.class).extracting("code").isEqualTo("orders.delivery-version-conflict");
        assertThat(status()).isEqualTo(ShipmentStatus.READY);
        desk.pickedUp(shipmentId, new DeliveryDeskService.Command(1L, "k3", null));
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void failedNeedsAReasonCountsTheAttemptAndCanBeReattempted() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        assertThatThrownBy(() -> desk.failed(shipmentId, " ", key("k4"))).isInstanceOf(OrdersRuleViolation.class)
                .extracting("code").isEqualTo("orders.failure-reason-required");
        DeliveryDeskService.Detail failed = desk.failed(shipmentId, "Nobody home, left a card", key("k5"));
        assertThat(status()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
        assertThat(failed.job().attempts()).isEqualTo(1);
        assertThat(failed.job().lastFailure()).isEqualTo("Nobody home, left a card");
        assertThat(failed.nextStatuses()).containsExactly("out_for_delivery", "returned");

        desk.outForDelivery(shipmentId, key("k6"));
        assertThat(status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(store.audit(shipmentId)).extracting(l -> l.action())
                .containsExactly("TAKE", "PICKED_UP", "OUT_FOR_DELIVERY", "FAILED", "REATTEMPT");
    }

    @Test
    void assignToAnotherStaffMemberByNameOnlyWhenTheyCanManageDeliveries() {
        DeliveryDeskService.Detail d = desk.assign(shipmentId, "courier-2", key("k1"));
        assertThat(d.row().assigneeName()).isEqualTo("Segun Lawal");
        assertThatThrownBy(() -> desk.assign(shipmentId, "someone-else", key("k2")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code").isEqualTo("orders.delivery-assignee-invalid");
        assertThatThrownBy(() -> desk.take(shipmentId, key("k3"))).isInstanceOf(OrdersStateConflict.class)
                .extracting("code").isEqualTo("orders.delivery-assigned");
        DeliveryDeskService.Detail again = desk.assign(shipmentId, "ops-user", key("k4"));
        assertThat(again.row().assigneeName()).isEqualTo("Chidi Nwosu");
        assertThat(store.audit(shipmentId)).extracting(l -> l.action()).containsExactly("ASSIGN", "REASSIGN");
    }

    @Test
    void aShipmentThatIsNotReadyCannotBeTaken() {
        place(FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.AFROTRANSACT_DELIVERY, null, ShipmentStatus.PACKED);
        assertThatThrownBy(() -> desk.take(shipmentId, key("k1"))).isInstanceOf(OrdersStateConflict.class)
                .extracting("code").isEqualTo("orders.delivery-not-ready");
    }

    @Test
    void sellerStoreDeliveryIsInTheDeskToo() {
        place(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(),
                ShipmentStatus.READY);
        assertThat(desk.get(shipmentId).row().mode()).isEqualTo("AFROTRANSACT_DELIVERY");
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    /** The regression: a seller-shipped order is never in the desk, whatever its status. */
    @Test
    void sellerShippedAndMislabelledLegacyOrdersAreNeverInTheDesk() {
        place(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.READY);
        assertThatThrownBy(() -> desk.get(shipmentId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> desk.take(shipmentId, key("k1"))).isInstanceOf(NotFoundException.class);
        place(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY, null, ShipmentStatus.READY);
        assertThatThrownBy(() -> desk.take(shipmentId, key("k2"))).isInstanceOf(NotFoundException.class);
        place(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP, null, ShipmentStatus.READY);
        assertThatThrownBy(() -> desk.get(shipmentId)).isInstanceOf(NotFoundException.class);
        assertThat(store.jobs).isEmpty();
    }

    @Test
    void readersSeeTheNameAndCityButNotTheStreetPhoneOrNotes() {
        tenants.as(TenantFixture.staff("delivery:read"));
        DeliveryDeskService.Detail d = desk.get(shipmentId);
        assertThat(d.canManage()).isFalse();
        assertThat(d.destination().name()).isEqualTo("Ada Obi");
        assertThat(d.destination().city()).isEqualTo("Houston");
        assertThat(d.destination().line1()).isNull();
        assertThat(d.destination().phone()).isNull();
        assertThat(d.destination().instructions()).isNull();
        assertThat(d.nextStatuses()).isEmpty();
    }

    @Test
    void managersSeeTheFullAddressAndTheNextSteps() {
        DeliveryDeskService.Detail d = desk.get(shipmentId);
        assertThat(d.canManage()).isTrue();
        assertThat(d.destination().line1()).isEqualTo("1 Main St");
        assertThat(d.destination().instructions()).isEqualTo("Gate code 4411");
        assertThat(d.nextStatuses()).containsExactly("picked_up");
    }

    @Test
    void permissionsAreEnforcedOnTheServer() {
        tenants.as(TenantFixture.staff("delivery:read"));
        assertThatThrownBy(() -> desk.take(shipmentId, key("k1"))).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> desk.delivered(shipmentId, proof(), key("k2")))
                .isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.staff("orders:manage", "orders:read", "delivery:dispatch"));
        assertThatThrownBy(() -> desk.list(new DeliveryDeskService.Query(null, null, null, null, null, null, null,
                false), 0, 25)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> desk.take(shipmentId, key("k3"))).isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.sellerOf("org-seller", "orders:read", "orders:update-status", "delivery:manage"));
        assertThatThrownBy(() -> desk.get(shipmentId)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> desk.take(shipmentId, key("k4"))).isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.customer("cust-1", "delivery:manage"));
        assertThatThrownBy(() -> desk.take(shipmentId, key("k5"))).isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.driver("drv-1", "delivery:operate"));
        assertThatThrownBy(() -> desk.take(shipmentId, key("k6"))).isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.service("delivery:manage"));
        assertThatThrownBy(() -> desk.take(shipmentId, key("k7"))).isInstanceOf(ForbiddenActionException.class);
        assertThat(store.jobs).isEmpty();
    }

    @Test
    void aProofCorrectionNeedsAReasonAndKeepsTheOldValuesInTheAudit() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        desk.outForDelivery(shipmentId, key("k3"));
        desk.delivered(shipmentId, proof(), key("k4"));
        assertThatThrownBy(() -> desk.correctProof(shipmentId, proof(), " ", key("k5")))
                .isInstanceOf(OrdersRuleViolation.class).extracting("code")
                .isEqualTo("orders.proof-correction-reason-required");
        DeliveryDeskService.Detail d = desk.correctProof(shipmentId, new DeliveryDeskService.ProofInput(
                List.of(PHOTO_2), "Ada Obi", null, null, null), "Wrong photo attached", key("k6"));
        assertThat(d.proof().photoAssetIds()).containsExactly(PHOTO_2);
        assertThat(d.proof().corrections()).isEqualTo(1);
        assertThat(d.proof().correctedByName()).isEqualTo("Chidi Nwosu");
        assertThat(d.proof().deliveredAt()).isNotNull();
        var correction = store.audit(shipmentId).getLast();
        assertThat(correction.action()).isEqualTo("PROOF_CORRECTED");
        assertThat(correction.detail()).containsKey("before").containsKey("after")
                .containsEntry("reason", "Wrong photo attached");
    }

    @Test
    void aCorrectionNeedsAnExistingProofAndTheCurrentVersion() {
        assertThatThrownBy(() -> desk.correctProof(shipmentId, proof(), "why", key("k1")))
                .isInstanceOf(OrdersStateConflict.class).extracting("code").isEqualTo("orders.delivery-no-proof");
        desk.take(shipmentId, key("k2"));
        desk.pickedUp(shipmentId, key("k3"));
        desk.outForDelivery(shipmentId, key("k4"));
        desk.delivered(shipmentId, proof(), key("k5"));
        assertThatThrownBy(() -> desk.correctProof(shipmentId, proof(), "why",
                new DeliveryDeskService.Command(7L, "k6", null))).isInstanceOf(OrdersStateConflict.class)
                .extracting("code").isEqualTo("orders.delivery-version-conflict");
    }

    @Test
    void deliveredStatusMovesOnlyThroughOutForDelivery() {
        desk.take(shipmentId, key("k1"));
        desk.pickedUp(shipmentId, key("k2"));
        assertThatThrownBy(() -> desk.delivered(shipmentId, proof(), key("k3")))
                .isInstanceOf(RuntimeException.class);
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(store.proofs).isEmpty();
    }
}
