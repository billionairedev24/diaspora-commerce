package com.afrotransact.fulfilment.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.StopView;
import com.afrotransact.fulfilment.application.RunDispatchView;
import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.RunDispatch;
import com.afrotransact.fulfilment.domain.RunPickupPoint;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.domain.StopStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Delivery runs in the dispatch console (S264). */
final class RunDispatchDtos {

    private RunDispatchDtos() {
    }

    @Schema(name = "RunPickupPoint", description = "Where a pickup-point run hands every order over")
    record PickupPointView(String name, String line1, String city, String state, String postalCode, String hours,
                           String notes) {

        static PickupPointView of(RunPickupPoint p) {
            return p == null ? null : new PickupPointView(p.name(), p.line1(), p.city(), p.state(), p.postalCode(),
                    p.hours(), p.notes());
        }
    }

    @Schema(name = "RunRoute", description = "One route of a run: seller pickups first, then the drops")
    record RunRouteView(String id, String name,
                        @Schema(description = "PLANNED | ASSIGNED | IN_PROGRESS | COMPLETED | CANCELLED")
                        String status, String driverId, String vehicleId, boolean blocked, String blockedReason,
                        int packages, int bagsCollected, int bagsDelivered, int pickupStops, int pickupsDone,
                        int dropStops, int dropsDone, int dropsFailed,
                        @Schema(description = "The stops in visiting order; empty in the list of runs")
                        List<StopView> stops) {

        static RunRouteView of(DeliveryRoute r, boolean withStops) {
            int bags = 0;
            int collected = 0;
            int delivered = 0;
            for (RoutePackage p : r.packages()) {
                if (!p.status().active()) {
                    continue;
                }
                bags++;
                collected += p.status() != PackageStatus.EXPECTED && p.status() != PackageStatus.NOT_PICKED_UP ? 1 : 0;
                delivered += p.status() == PackageStatus.DELIVERED ? 1 : 0;
            }
            int pickups = 0;
            int pickupsDone = 0;
            int drops = 0;
            int dropsDone = 0;
            int dropsFailed = 0;
            for (RouteStop s : r.stops()) {
                if (s.status() == StopStatus.CANCELLED || s.status() == StopStatus.REASSIGNED) {
                    continue;
                }
                if (s.kind() == StopKind.PICKUP) {
                    pickups++;
                    pickupsDone += s.status() == StopStatus.DONE ? 1 : 0;
                } else if (s.kind() == StopKind.DROPOFF) {
                    drops++;
                    dropsDone += s.status() == StopStatus.DONE ? 1 : 0;
                    dropsFailed += s.status() == StopStatus.FAILED || s.status() == StopStatus.SKIPPED ? 1 : 0;
                }
            }
            return new RunRouteView(r.id(), r.name(), r.status().name(), r.driverId(), r.vehicleId(), r.blocked(),
                    r.blockedReason(), bags, collected, delivered, pickups, pickupsDone, drops, dropsDone, dropsFailed,
                    withStops ? r.stops().stream().map(StopView::of).toList() : List.of());
        }
    }

    @Schema(name = "RunProgress", description = "What has happened on the run's routes")
    record RunProgressView(int orders, int bags, int pickupStops, int pickupsDone, int bagsCollected, int dropStops,
                           int dropsDone, int dropsFailed, int bagsDelivered,
                           @Schema(description = "Run shipments waiting for a route (a route was cancelled, or "
                                   + "planning is not done)") int unrouted) {

        static RunProgressView of(RunDispatchView.Progress p) {
            return new RunProgressView(p.orders(), p.bags(), p.pickupStops(), p.pickupsDone(), p.bagsCollected(),
                    p.dropStops(), p.dropsDone(), p.dropsFailed(), p.bagsDelivered(), p.unrouted());
        }
    }

    @Schema(name = "RunDispatch", description = "A delivery run in dispatch: its schedule, routes and progress")
    record RunDispatchResponse(String runId, String town, String state, String timeZone, LocalDate serviceDate,
                               Instant windowStart, Instant windowEnd,
                               @Schema(description = "When the driver collects from the sellers") Instant sellerPickupAt,
                               @Schema(description = "DOORSTEP | PICKUP_POINT") String dropMode,
                               PickupPointView pickupPoint, String depotNodeId,
                               @Schema(description = "PENDING (not planned; see planError) | PLANNED | CANCELLED")
                               String status,
                               @Schema(description = "Why the routes could not be planned; plan again to retry")
                               String planError, Instant plannedAt, RunProgressView progress,
                               List<RunRouteView> routes) {

        static RunDispatchResponse of(RunDispatchView v, boolean withStops) {
            RunDispatch r = v.run();
            return new RunDispatchResponse(r.runId(), r.town(), r.state(), r.timeZone(), r.serviceDate(),
                    r.windowStart(), r.windowEnd(), r.sellerPickupAt(), r.doorstep() ? "DOORSTEP" : "PICKUP_POINT",
                    PickupPointView.of(r.pickupPoint()), r.depotNodeId(), r.status().name(), r.planError(),
                    r.plannedAt(), RunProgressView.of(v.progress()),
                    v.routes().stream().map(x -> RunRouteView.of(x, withStops)).toList());
        }
    }
}
