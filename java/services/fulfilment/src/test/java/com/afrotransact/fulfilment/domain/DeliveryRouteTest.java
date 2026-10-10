package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryRouteTest {

    private static final Instant T = Instant.parse("2026-10-10T18:00:00Z");
    private static final String FC_1 = "ATS7K2Q9H4M1";
    private static final String FC_2 = "ATS3NP8D2LQ6";
    private static final String SELLER_1 = "ATS5RM2J7VZ3";
    private static final String SELLER_2 = "ATS8HD6K3PF2";

    private final PickupPolicy pickups = new DefaultPickupPolicy();
    private final ReattemptPolicy reattempts = new DefaultReattemptPolicy();
    private DeliveryRoute route;

    @BeforeEach
    void route() {
        route = DeliveryRoute.plan("RT-HOU-PM", "Houston SW #1", "Houston SW", LocalDate.of(2026, 10, 10),
                "1:00–5:00 pm", "HOU-FC1");
        route.assign("driver-tunde", "TX-7P10");
        RouteStop pickup = RouteStop.pending("p1", StopKind.PICKUP, 0, "Mama Tee's Pantry", "org-mt", null, null,
                null, null, null, null, false, false, T);
        route.addPickupStop(pickup);
        RouteStop s3 = RouteStop.pending("s3", StopKind.DROPOFF, 0, "Order 1", null, "112-4401731", null, "Houston",
                "77042", Photos.ADDRESS, null, true, false, T);
        route.addStop(s3, List.of(pkg("k1", FC_1, "sh1", PackageOrigin.FC, null, "s3", false),
                pkg("k2", SELLER_1, "sh2", PackageOrigin.SELLER, "p1", "s3", false)));
        RouteStop s5 = RouteStop.pending("s5", StopKind.DROPOFF, 0, "Order 2", null, "112-4401907", null, "Houston",
                "77081", null, null, true, false, T);
        route.addStop(s5, List.of(pkg("k3", FC_2, "sh3", PackageOrigin.FC, null, "s5", false),
                pkg("k4", SELLER_2, "sh4", PackageOrigin.SELLER, "p1", "s5", true)));
    }

    private static RoutePackage pkg(String id, String label, String shipment, PackageOrigin origin, String pickup,
                                    String drop, boolean perishable) {
        return new RoutePackage(id, label, shipment, "O-" + shipment, origin, pickup, drop, perishable,
                PackageStatus.EXPECTED, false, false, false, T);
    }

    private void inspectAndLoad() {
        route.recordInspection("TX-7P10", new InspectionOutcome(InspectionResult.PASSED, List.of(), List.of(),
                List.of()), 48_222, null);
        route.scanLoadOut(FC_1, false, T);
        route.scanLoadOut(FC_2, false, T);
        route.completeLoadOut(T);
        route.start(T);
    }

    @Test
    void sellerPickupsComeBeforeDropoffsAndPerishablesAreDetected() {
        assertThat(route.stops()).extracting(RouteStop::id).containsExactly("p1", "s3", "s5");
        assertThat(route.perishable()).isTrue();
    }

    @Test
    void loadOutRejectsUnknownAndAlreadyScannedLabelsAndFlagsManualEntry() {
        assertThatThrownBy(() -> route.scanLoadOut(FC_1, false, T)).extracting("code")
                .isEqualTo("fulfilment.inspection-required");
        route.recordInspection("TX-7P10", new InspectionOutcome(InspectionResult.PASSED, List.of(), List.of(),
                List.of()), 48_222, null);
        assertThatThrownBy(() -> route.scanLoadOut("NOT-A-LABEL", false, T)).extracting("code")
                .isEqualTo("fulfilment.label-invalid");
        assertThatThrownBy(() -> route.scanLoadOut("ATS000000000", false, T)).extracting("code")
                .isEqualTo("fulfilment.label-unknown");
        assertThatThrownBy(() -> route.scanLoadOut(SELLER_1, false, T)).as("seller packages are not on the FC "
                + "manifest").extracting("code").isEqualTo("fulfilment.label-unknown");
        RoutePackage typed = route.scanLoadOut(" ats7k2q9h4m1 ", true, T);
        assertThat(typed.manualEntry()).isTrue();
        assertThat(typed.status()).isEqualTo(PackageStatus.LOADED);
        assertThatThrownBy(() -> route.scanLoadOut(FC_1, false, T)).isInstanceOf(FulfilmentConflict.class)
                .extracting("code").isEqualTo("fulfilment.label-already-scanned");
        assertThatThrownBy(() -> route.completeLoadOut(T)).extracting("code")
                .isEqualTo("fulfilment.loadout-incomplete");
        assertThatThrownBy(() -> route.start(T)).extracting("code").isEqualTo("fulfilment.loadout-required");
        route.scanLoadOut(FC_2, false, T);
        assertThat(route.completeLoadOut(T)).extracting(RoutePackage::label).containsExactlyInAnyOrder(FC_1, FC_2);
        assertThat(route.start(T)).hasSize(2);
    }

    @Test
    void criticalDefectBlocksTheRouteUntilCleared() {
        route.recordInspection("TX-7P10", new InspectionOutcome(InspectionResult.FAILED_CRITICAL,
                List.of(InspectionItem.BRAKES), List.of(InspectionItem.BRAKES), List.of()), 48_222, "Soft pedal");
        assertThat(route.blocked()).isTrue();
        assertThatThrownBy(() -> route.start(T)).extracting("code").isEqualTo("fulfilment.route-blocked");
        assertThatThrownBy(() -> route.scanLoadOut(FC_1, false, T)).extracting("code")
                .isEqualTo("fulfilment.inspection-required");
        route.assign("driver-tunde", "TX-9Q22");
        assertThat(route.blocked()).as("new vehicle needs a new inspection").isFalse();
        assertThat(route.inspection()).isNull();
    }

    @Test
    void dropoffNeedsTheSellerPickupFirst() {
        inspectAndLoad();
        assertThatThrownBy(() -> route.arrive("s3", T)).extracting("code")
                .isEqualTo("fulfilment.package-not-on-board");
    }

    @Test
    void pickupConfirmationNeedsAllScansOrAReasonAndTheHandoverName() {
        inspectAndLoad();
        route.arrive("p1", T);
        route.scanAtStop("p1", SELLER_1, false, T);
        assertThatThrownBy(() -> route.scanAtStop("p1", SELLER_1, false, T)).extracting("code")
                .isEqualTo("fulfilment.label-already-scanned");
        assertThatThrownBy(() -> route.scanAtStop("p1", FC_1, false, T)).extracting("code")
                .isEqualTo("fulfilment.label-unknown");
        assertThatThrownBy(() -> route.confirmPickup("p1", "Titi", null, pickups, T)).extracting("code")
                .isEqualTo("fulfilment.pickup-scans-incomplete");
        assertThatThrownBy(() -> route.confirmPickup("p1", "T", "Tray still cooking", pickups, T)).extracting("code")
                .isEqualTo("fulfilment.handed-over-by-required");
        List<RoutePackage> collected = route.confirmPickup("p1", "Titi", "Tray still cooking", pickups, T);
        assertThat(collected).extracting(RoutePackage::label).containsExactly(SELLER_1);
        assertThat(route.stop("p1").status()).isEqualTo(StopStatus.DONE);
        assertThat(route.stop("p1").handedOverBy()).isEqualTo("Titi");
        assertThat(route.packages()).filteredOn(p -> p.label().equals(SELLER_2)).first()
                .extracting(RoutePackage::status).isEqualTo(PackageStatus.NOT_PICKED_UP);
        // s5 still has its FC package, so it is not skipped.
        assertThat(route.stop("s5").status()).isEqualTo(StopStatus.PENDING);
    }

    @Test
    void pickupProblemsWaitSkipOrTakePartial() {
        inspectAndLoad();
        route.arrive("p1", T);
        assertThat(route.pickupProblem("p1", new PickupProblem(PickupProblemType.NOT_READY, PickupResolution.WAIT,
                "Seller still packing", null, T), pickups, T)).isEqualTo(StopOutcome.CONTINUE);
        assertThatThrownBy(() -> route.pickupProblem("p1", new PickupProblem(PickupProblemType.CLOSED,
                PickupResolution.PARTIAL, "Shop closed", null, T), pickups, T)).extracting("code")
                .isEqualTo("fulfilment.resolution-not-allowed");
        assertThatThrownBy(() -> route.pickupProblem("p1", new PickupProblem(PickupProblemType.WRONG_COUNT,
                PickupResolution.PARTIAL, "Only one bag", null, T), pickups, T)).extracting("code")
                .isEqualTo("fulfilment.problem-photo-required");
        route.scanAtStop("p1", SELLER_1, false, T);
        route.pickupProblem("p1", new PickupProblem(PickupProblemType.WRONG_COUNT, PickupResolution.PARTIAL,
                "Only one bag ready", Photos.live("RT-HOU-PM"), T), pickups, T);
        assertThat(route.confirmPickup("p1", "Titi", null, pickups, T)).hasSize(1);
    }

    @Test
    void closedSellerSkipsThePickupAndDropoffsWithNothingToDeliver() {
        RouteStop s9 = RouteStop.pending("s9", StopKind.DROPOFF, 0, "Order 9", null, "O9", null, null, "77074", null,
                null, false, true, T);
        route.addStop(s9, List.of(pkg("k9", "ATS999999999", "sh9", PackageOrigin.SELLER, "p1", "s9", false)));
        inspectAndLoad();
        route.arrive("p1", T);
        route.pickupProblem("p1", new PickupProblem(PickupProblemType.CLOSED, PickupResolution.SKIP,
                "Nobody at the shop", null, T), pickups, T);
        assertThat(route.stop("p1").status()).isEqualTo(StopStatus.SKIPPED);
        assertThat(route.stop("s9").status()).isEqualTo(StopStatus.SKIPPED);
        assertThat(route.stop("s3").status()).as("still has an FC package").isEqualTo(StopStatus.PENDING);
    }

    @Test
    void reattemptsMustBeResolvedAndFailedPackagesScannedBackBeforeShiftEnd() {
        inspectAndLoad();
        route.arrive("p1", T);
        route.scanAtStop("p1", SELLER_1, false, T);
        route.scanAtStop("p1", SELLER_2, true, T);
        route.confirmPickup("p1", "Titi", null, pickups, T);

        route.arrive("s3", T);
        route.logContact("s3", ContactKind.CALL, T);
        route.logContact("s3", ContactKind.TEXT, T);
        route.logContact("s3", ContactKind.WAIT_STARTED, T);
        Instant later = T.plusSeconds(301);
        route.reportProblem("s3", new DeliveryProblem(DeliveryProblemType.CUSTOMER_UNAVAILABLE,
                ProblemResolution.REATTEMPT, null, null, later), reattempts, later);
        assertThat(route.stop("s3").status()).isEqualTo(StopStatus.RETRY);
        assertThat(route.stop("s3").contacts()).as("a new attempt starts a new contact log").isEmpty();

        route.arrive("s5", T);
        route.reportProblem("s5", new DeliveryProblem(DeliveryProblemType.REFUSED, ProblemResolution.RETURN_TO_FC,
                "Customer refused the tray", null, T), reattempts, T);
        assertThatThrownBy(() -> route.endShift(48_300, T)).extracting("code")
                .isEqualTo("fulfilment.stops-unresolved");

        route.arrive("s3", later);
        assertThatThrownBy(() -> route.reportProblem("s3", new DeliveryProblem(
                DeliveryProblemType.CUSTOMER_UNAVAILABLE, ProblemResolution.RETURN_TO_FC, null, null, later),
                reattempts, later)).extracting("code").isEqualTo("fulfilment.contact-policy-not-met");
        route.logContact("s3", ContactKind.CALL, later);
        route.logContact("s3", ContactKind.TEXT, later);
        route.logContact("s3", ContactKind.WAIT_STARTED, later);
        Instant end = later.plusSeconds(300);
        route.reportProblem("s3", new DeliveryProblem(DeliveryProblemType.CUSTOMER_UNAVAILABLE,
                ProblemResolution.PICKUP_POINT, null, null, end), reattempts, end);

        assertThatThrownBy(() -> route.endShift(48_300, end)).extracting("code")
                .isEqualTo("fulfilment.returns-not-scanned");
        assertThatThrownBy(() -> route.scanReturn("ATS000000001", false, end)).extracting("code")
                .isEqualTo("fulfilment.label-unknown");
        for (String label : List.of(FC_1, SELLER_1, FC_2, SELLER_2)) {
            route.scanReturn(label, false, end);
        }
        assertThatThrownBy(() -> route.scanReturn(FC_1, false, end)).extracting("code")
                .isEqualTo("fulfilment.label-already-scanned");
        assertThatThrownBy(() -> route.endShift(48_222, end)).extracting("code")
                .isEqualTo("fulfilment.odometer-invalid");
        route.endShift(48_301, end);
        assertThat(route.status()).isEqualTo(RouteStatus.COMPLETED);
    }

    @Test
    void failedStopsAreDetachedForReassignment() {
        inspectAndLoad();
        route.arrive("p1", T);
        route.pickupProblem("p1", new PickupProblem(PickupProblemType.CLOSED, PickupResolution.SKIP,
                "Nobody at the shop", null, T), pickups, T);
        assertThatThrownBy(() -> route.detachForReassignment("p1", T)).extracting("code")
                .isEqualTo("fulfilment.reassign-dropoffs-only");
        List<RoutePackage> moving = route.detachForReassignment("s5", T);
        assertThat(moving).extracting(RoutePackage::label).containsExactlyInAnyOrder(FC_2, SELLER_2);
        assertThat(route.stop("s5").status()).isEqualTo(StopStatus.REASSIGNED);
        assertThat(route.packages()).filteredOn(p -> p.label().equals(FC_2)).first()
                .extracting(RoutePackage::status).as("on the van: back to the FC").isEqualTo(PackageStatus.FAILED);
        assertThatThrownBy(() -> route.detachForReassignment("s5", T)).extracting("code")
                .isEqualTo("fulfilment.stop-not-reassignable");
    }
}
