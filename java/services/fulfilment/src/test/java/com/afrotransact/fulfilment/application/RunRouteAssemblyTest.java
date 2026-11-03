package com.afrotransact.fulfilment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.RunBag;
import com.afrotransact.fulfilment.domain.RunDispatch;
import com.afrotransact.fulfilment.domain.RunDispatchStatus;
import com.afrotransact.fulfilment.domain.RunDrop;
import com.afrotransact.fulfilment.domain.RunPickupPoint;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.port.IdIssuer;
import org.junit.jupiter.api.Test;

/** S264: a run's route has the sellers' pickups first, then one drop per order; a package is one seller's bag. */
class RunRouteAssemblyTest {

    private static final Instant NOW = Instant.parse("2026-10-24T14:00:00Z");
    private static final GeoPoint HUB = new GeoPoint(30.2672, -97.7431);
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final RouteAssembler assembler = new RouteAssembler(new IdIssuer() {
        @Override
        public String packageLabel() {
            return "ATS" + String.format("%09d", SEQ.incrementAndGet());
        }

        @Override
        public String routeId() {
            return "RT-TEST" + SEQ.incrementAndGet();
        }
    });

    private static RunDispatch run(boolean doorstep) {
        return new RunDispatch("run-1", "Bastrop", "TX", "America/Chicago", LocalDate.of(2026, 10, 24), NOW,
                NOW.plusSeconds(43_200), NOW.minusSeconds(86_400), doorstep, doorstep ? null : new RunPickupPoint(
                "Bastrop Public Library", "1100 Church St", "Bastrop", "TX", "78602", "Sat 2-5 PM", null), HUB,
                "HOU-FC1", RunDispatchStatus.PENDING, null, null, 0);
    }

    private static DeliveryShipment shipment(String id, String order, boolean handover, boolean perishable,
                                             GeoPoint where) {
        return new DeliveryShipment(id, "id-" + order, order, "cust-" + order, "org-mama", "Mama Tee's Pantry",
                "PREORDER", PackageOrigin.SELLER, null, "ATS00000000" + id.substring(id.length() - 1), "Bastrop", "TX",
                "78602", "US", where, perishable, handover, 3, DeliveryShipmentStatus.AWAITING_ROUTE, null,
                LocalDate.of(2026, 10, 24), NOW, null);
    }

    private static RunBag bag(String shipment, String seller, String name, String label, int units) {
        return new RunBag(shipment, seller, name, label, units);
    }

    private DeliveryRoute route() {
        return DeliveryRoute.plan("RT-1", "Bastrop run", "Bastrop", LocalDate.of(2026, 10, 24), "9:00 AM – 9:00 PM",
                "HOU-FC1");
    }

    @Test
    void sellersAreVisitedFirstByNameThenEachOrderIsOneDrop() {
        DeliveryShipment s1 = shipment("shp-1", "AT-1", false, false, new GeoPoint(30.11, -97.31));
        DeliveryShipment s2 = shipment("shp-2", "AT-2", true, false, new GeoPoint(30.12, -97.32));
        Map<String, List<RunBag>> bags = Map.of(
                "shp-1", List.of(bag("shp-1", "org-mama", "Mama Tee's Pantry", "ATS000000101", 2),
                        bag("shp-1", "org-lagos", "Lagos Oils", "ATS000000102", 1)),
                "shp-2", List.of(bag("shp-2", "org-mama", "Mama Tee's Pantry", "ATS000000103", 3)));
        DeliveryRoute r = route();

        List<String> added = assembler.addRun(r, List.of(new RunDrop("AT-2", List.of("shp-2"), 1, 5.0),
                new RunDrop("AT-1", List.of("shp-1"), 2, 9.0)), Map.of("shp-1", s1, "shp-2", s2), bags,
                run(true), seller -> false, NOW);

        assertThat(added).containsExactly("shp-2", "shp-1");
        assertThat(r.stops()).extracting(RouteStop::kind, RouteStop::name).containsExactly(
                tuple(StopKind.PICKUP, "Lagos Oils"),
                tuple(StopKind.PICKUP, "Mama Tee's Pantry"),
                tuple(StopKind.DROPOFF, "Order AT-2"),
                tuple(StopKind.DROPOFF, "Order AT-1"));
        assertThat(r.stops()).extracting(RouteStop::sequence).containsExactly(1, 2, 3, 4);
        assertThat(r.packages()).hasSize(3).allSatisfy(p -> {
            assertThat(p.origin()).isEqualTo(PackageOrigin.SELLER);
            assertThat(p.status()).isEqualTo(PackageStatus.EXPECTED);
        });
        RouteStop lagos = r.stops().get(0);
        RouteStop mama = r.stops().get(1);
        assertThat(r.packagesForPickup(lagos.id())).extracting(RoutePackage::label).containsExactly("ATS000000102");
        assertThat(r.packagesForPickup(mama.id())).extracting(RoutePackage::label)
                .containsExactlyInAnyOrder("ATS000000101", "ATS000000103");
        RouteStop dropAt1 = r.stops().get(3);
        assertThat(r.packagesForDrop(dropAt1.id())).extracting(RoutePackage::label)
                .containsExactlyInAnyOrder("ATS000000101", "ATS000000102");
        assertThat(dropAt1.handoverRequired()).isFalse();
        assertThat(dropAt1.safePlaceAllowed()).isTrue();
        assertThat(r.stops().get(2).handoverRequired()).as("a hand-delivery order keeps its code").isTrue();
        assertThat(dropAt1.point()).isEqualTo(new GeoPoint(30.11, -97.31));
    }

    @Test
    void aPickupPointRunMakesOneDropPerOrderAtThePointWithTheCustomersCodeRequired() {
        DeliveryShipment s1 = shipment("shp-1", "AT-1", true, false, null);
        DeliveryShipment s2 = shipment("shp-2", "AT-2", true, false, null);
        Map<String, List<RunBag>> bags = Map.of(
                "shp-1", List.of(bag("shp-1", "org-mama", "Mama Tee's Pantry", "ATS000000201", 1)),
                "shp-2", List.of(bag("shp-2", "org-mama", "Mama Tee's Pantry", "ATS000000202", 1)));
        DeliveryRoute r = route();

        assembler.addRun(r, List.of(new RunDrop("AT-1", List.of("shp-1"), 1, null),
                new RunDrop("AT-2", List.of("shp-2"), 1, null)), Map.of("shp-1", s1, "shp-2", s2), bags, run(false),
                seller -> false, NOW);

        assertThat(r.stops()).extracting(RouteStop::kind).containsExactly(StopKind.PICKUP, StopKind.DROPOFF,
                StopKind.DROPOFF);
        for (RouteStop drop : r.stops().subList(1, 3)) {
            assertThat(drop.name()).endsWith("Bastrop Public Library");
            assertThat(drop.addressLine()).isEqualTo("Bastrop Public Library, 1100 Church St");
            assertThat(drop.postalCode()).isEqualTo("78602");
            assertThat(drop.handoverRequired()).isTrue();
            assertThat(drop.safePlaceAllowed()).as("never left at a pickup point").isFalse();
            assertThat(drop.point()).isNull();
        }
    }

    @Test
    void sellersWhoseGoodsAreAtTheDepotAreLoadedThereNotCollected() {
        DeliveryShipment s1 = shipment("shp-1", "AT-1", false, false, new GeoPoint(30.11, -97.31));
        Map<String, List<RunBag>> bags = Map.of("shp-1", List.of(
                bag("shp-1", "org-retail", "AfroTransact Retail", "ATS000000301", 2),
                bag("shp-1", "org-mama", "Mama Tee's Pantry", "ATS000000302", 1)));
        DeliveryRoute r = route();

        assembler.addRun(r, List.of(new RunDrop("AT-1", List.of("shp-1"), 2, 9.0)), Map.of("shp-1", s1), bags,
                run(true), "org-retail"::equals, NOW);

        assertThat(r.stops()).extracting(RouteStop::name).containsExactly("Mama Tee's Pantry", "Order AT-1");
        assertThat(r.fcPackages()).extracting(RoutePackage::label).containsExactly("ATS000000301");
        RoutePackage collected = r.packages().stream().filter(p -> p.label().equals("ATS000000302")).findFirst()
                .orElseThrow();
        assertThat(collected.origin()).isEqualTo(PackageOrigin.SELLER);
        assertThat(collected.pickupStopId()).isEqualTo(r.stops().get(0).id());
    }

    @Test
    void aShipmentWithoutRecordedBagsIsOnePackageUnderItsOwnLabel() {
        DeliveryShipment s1 = shipment("shp-1", "AT-1", false, true, new GeoPoint(30.11, -97.31));
        DeliveryRoute r = route();

        assembler.addRun(r, List.of(new RunDrop("AT-1", List.of("shp-1"), 1, 9.0)), Map.of("shp-1", s1), Map.of(),
                run(true), seller -> false, NOW);

        assertThat(r.packages()).singleElement().satisfies(p -> {
            assertThat(p.label()).isEqualTo(s1.label());
            assertThat(p.perishable()).isTrue();
        });
        assertThat(r.stops().get(1).safePlaceAllowed()).as("perishables are handed to a person").isFalse();
    }
}
