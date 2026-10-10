package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.inventory.application.CommitResult;
import com.afrotransact.inventory.application.InventoryEvents;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.application.ReturnCommand;
import com.afrotransact.inventory.application.ReturnService;
import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;
import com.afrotransact.inventory.domain.ReservationStatus;
import com.afrotransact.inventory.domain.ReturnCondition;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S218 per-shipment commit / release, S226 stock owner and hold reason, S235 service returns + receiving node. */
class ReservationGroupsIT extends InventoryIntegrationTest {

    private static final Actor RETURNS_SERVICE = new Actor("svc-tax-returns", PrincipalType.SERVICE,
            Optional.empty(), Set.of(), Set.of("inventory:returns"));

    private ReservationService reservations;
    private ReturnService returns;

    @BeforeEach
    void wire(@Autowired ReservationService reservationService, @Autowired ReturnService returnService) {
        this.reservations = reservationService;
        this.returns = returnService;
    }

    @Test
    void cancellingOneShipmentReleasesOnlyItsGroups() {
        String rice = sku("GRP-RICE");
        String oil = sku("GRP-OIL");
        stock(rice, 10);
        stock(oil, 10);
        String orderId = "ORD-" + UUID.randomUUID();
        TestActors.as(TestActors.ORDERS_SERVICE);
        Reservation held = reservations.reserve(new ReserveCommand(orderId, "k", List.of(
                new ReserveCommand.Line(rice, null, 3, "line-1"), new ReserveCommand.Line(oil, null, 2, "line-2"))))
                .reservation();
        assertThat(held.lines()).extracting(ReservationLine::group).containsExactly("line-1", "line-2");
        assertThat(held.lines()).extracting(ReservationLine::stockOwnerOrgId).containsOnly(RETAIL);
        reservations.confirm(orderId);

        Reservation afterCancel = reservations.releaseGroups(orderId, Set.of("line-2"), "Shipment 2 cancelled");
        assertThat(afterCancel.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(position(oil, "reserved")).isZero();
        assertThat(position(rice, "reserved")).isEqualTo(3);
        assertThat(events(InventoryEvents.RESERVATION_GROUP_RELEASED, orderId)).isEqualTo(1);
        // idempotent
        assertThat(reservations.releaseGroups(orderId, Set.of("line-2"), null).status())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(events(InventoryEvents.RESERVATION_GROUP_RELEASED, orderId)).isEqualTo(1);

        CommitResult shipped = reservations.commitGroups(orderId, Set.of("line-1"));
        assertThat(shipped.reservation().status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(shipped.lines()).singleElement().satisfies(l -> {
            assertThat(l.group()).isEqualTo("line-1");
            assertThat(l.stockOwnerOrgId()).isEqualTo(RETAIL);
        });
        assertThat(position(rice, "on_hand")).isEqualTo(7);
        assertThat(position(oil, "on_hand")).isEqualTo(10);
        assertThat(reservations.commitGroups(orderId, Set.of("line-1")).replayed()).isTrue();
        assertThat(events(InventoryEvents.RESERVATION_GROUP_COMMITTED, orderId)).isEqualTo(1);
        assertThat(jdbc.sql("""
                select (payload -> 'lines' -> 0 ->> 'stockOwnerOrgId') || '|' || (payload -> 'groups' ->> 0)
                from platform_outbox where type = :t and aggregate_id = :id
                """).param("t", InventoryEvents.RESERVATION_GROUP_COMMITTED).param("id", orderId)
                .query(String.class).single()).isEqualTo(RETAIL + "|line-1");

        assertThatThrownBy(() -> reservations.commitGroups(orderId, Set.of("line-2")))
                .isInstanceOfSatisfying(InventoryStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.group-released"));
        assertThatThrownBy(() -> reservations.releaseGroups(orderId, Set.of("line-1"), "late"))
                .isInstanceOfSatisfying(InventoryStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.group-committed"));
        assertThatThrownBy(() -> reservations.commitGroups(orderId, Set.of("nope")))
                .isInstanceOf(NotFoundException.class);
        assertThat(reconcile().clean()).isTrue();
    }

    @Test
    void wholeOrderCommitShipsOnlyLinesStillHeld() {
        String a = sku("GRP-A");
        String b = sku("GRP-B");
        stock(a, 5);
        stock(b, 5);
        String orderId = "ORD-" + UUID.randomUUID();
        TestActors.as(TestActors.ORDERS_SERVICE);
        reservations.reserve(new ReserveCommand(orderId, "k", List.of(new ReserveCommand.Line(a, NODE, 1, "l1"),
                new ReserveCommand.Line(b, NODE, 2, "l2"))));
        reservations.releaseGroups(orderId, Set.of("l1"), "cancelled");
        CommitResult all = reservations.commit(orderId);
        assertThat(all.lines()).extracting(CommitResult.LineCost::group).containsExactly("l2");
        assertThat(position(a, "on_hand")).isEqualTo(5);
        assertThat(position(b, "on_hand")).isEqualTo(3);
    }

    @Test
    void serviceReturnsGoToTheReceivingWarehouse() {
        String sku = sku("RET-RC");
        stock(sku, 5);
        String orderId = "ORD-" + UUID.randomUUID();
        TestActors.as(TestActors.ORDERS_SERVICE);
        reservations.reserve(new ReserveCommand(orderId, "k", List.of(new ReserveCommand.Line(sku, NODE, 2, "l1"))));
        reservations.commitGroups(orderId, Set.of("l1"));

        TestActors.as(TestActors.staff("inventory:read"));
        assertThatThrownBy(() -> returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.RESTOCK,
                "Unopened", "ATL-FC"), "rr-forbidden-" + sku)).isInstanceOf(ForbiddenActionException.class);

        TestActors.as(RETURNS_SERVICE);
        Lot back = returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.RESTOCK, "Unopened",
                "ATL-FC"), "rr1-" + sku).lot();
        assertThat(back.nodeId()).isEqualTo("ATL-FC");
        assertThat(back.status()).isEqualTo(LotStatus.SELLABLE);
        Lot dmg = returns.accept(new ReturnCommand(orderId, sku, null, 1, ReturnCondition.DAMAGED, "Crushed",
                "ATL-FC"), "rr2-" + sku).lot();
        assertThat(dmg.nodeId()).isEqualTo("ATL-FC");
        assertThat(dmg.lotNo()).endsWith("-DMG");
        assertThat(jdbc.sql("select on_hand from inventory_position where sku = :sku and node_id = 'ATL-FC'")
                .param("sku", sku).query(Integer.class).single()).isEqualTo(1);
        assertThat(position(sku, "on_hand")).isEqualTo(3);
        assertThat(reconcile().clean()).isTrue();
    }

    @Test
    void receiptLinesWithHoldReasonArriveQuarantined() {
        String sku = sku("HOLD");
        List<Lot> lots = receive(NODE, new ReceiveCommand.Line(sku, "L-HOLD-1", today().minusDays(30),
                today().plusDays(500), 4, 0, "A-01-01", 100, "USD", null, "QC: seal check")).lots();
        assertThat(lots).singleElement().satisfies(l -> {
            assertThat(l.status()).isEqualTo(LotStatus.QUARANTINE);
            assertThat(l.reason()).contains("QC: seal check");
        });
        assertThat(position(sku, "damaged")).isEqualTo(4);
        assertThat(events(InventoryEvents.LOT_QUARANTINED, sku)).isEqualTo(1);
    }
}
