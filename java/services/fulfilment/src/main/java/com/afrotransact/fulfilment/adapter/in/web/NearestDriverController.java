package com.afrotransact.fulfilment.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.RouteView;
import com.afrotransact.fulfilment.application.DriverLocationService;
import com.afrotransact.fulfilment.application.LocationPing;
import com.afrotransact.fulfilment.application.PickupAssignmentService;
import com.afrotransact.fulfilment.domain.DriverPosition;
import com.afrotransact.fulfilment.domain.DriverSuggestion;
import com.afrotransact.fulfilment.port.PickupAssignmentLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** S255: driver positions and the nearest-driver preference for seller pickups. */
@RestController
class NearestDriverController {

    private final DriverLocationService locations;
    private final PickupAssignmentService pickups;
    private final Credentials credentials;

    NearestDriverController(DriverLocationService locations, PickupAssignmentService pickups,
                            Credentials credentials) {
        this.locations = locations;
        this.pickups = pickups;
        this.credentials = credentials;
    }

    @Schema(name = "DriverLocationPing", description = "The device's current GPS fix")
    record LocationRequest(@Size(max = 120) String deviceId,
                           @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                           @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon,
                           @PositiveOrZero Double accuracyM,
                           @Schema(description = "Device time of the fix; default now") Instant capturedAt) {
    }

    @Schema(name = "DriverPosition", description = "The driver's last known position")
    record PositionView(String driverId, double lat, double lon, Double accuracyM, Instant recordedAt,
                        @Schema(allowableValues = {"SYNC", "PING"}) String source) {

        static PositionView of(DriverPosition p) {
            return new PositionView(p.driverId(), p.point().lat(), p.point().lon(), p.accuracyM(), p.recordedAt(),
                    p.source());
        }
    }

    @Schema(name = "DriverSuggestion", description = "An available driver for a seller pickup, nearest first")
    record SuggestionView(String driverId, String routeId, String routeName, String zone, String routeStatus,
                          @Schema(description = "PostGIS geodesic miles from the driver's last position")
                          double straightLineMiles,
                          @Schema(description = "Road miles from geo's routing provider (null when geo is down)")
                          Double roadMiles, Double durationMinutes,
                          @Schema(description = "ROAD_GOOGLE, ROAD_OSRM, GEODESIC_DETOUR or STRAIGHT_LINE")
                          String distanceMethod, Instant positionAt, int packagesOnRoute, int capacityLeft) {

        static SuggestionView of(DriverSuggestion s) {
            return new SuggestionView(s.driverId(), s.routeId(), s.routeName(), s.zone(), s.routeStatus(),
                    s.straightLineMiles(), s.roadMiles(), s.durationMinutes(), s.distanceMethod(), s.positionAt(),
                    s.packagesOnRoute(), s.capacityLeft());
        }
    }

    @Schema(name = "DriverSuggestions")
    record SuggestionsView(String shipmentId, List<SuggestionView> drivers) {
    }

    @Schema(name = "PickupAssignRequest", description = "Dispatch override of the nearest-driver assignment")
    record PickupAssignRequest(@NotBlank @Size(max = 20) String routeId,
                               @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    @Schema(name = "PickupAssignment", description = "One assignment of a seller pickup")
    record AssignmentView(UUID id, String shipmentId, String routeId, String driverId,
                          @Schema(allowableValues = {"AUTO_NEAREST", "DISPATCH_OVERRIDE"}) String mode,
                          String reason, String previousRouteId, Double straightMiles, Double roadMiles,
                          String distanceMethod, String actor, Instant createdAt) {

        static AssignmentView of(PickupAssignmentLog.Entry e) {
            return new AssignmentView(e.id(), e.shipmentId(), e.routeId(), e.driverId(), e.mode(), e.reason(),
                    e.previousRouteId(), e.straightMiles(), e.roadMiles(), e.distanceMethod(), e.actor(),
                    e.createdAt());
        }
    }

    @Tag(name = "Driver app")
    @Operation(operationId = "driverLocation", summary = "Report my current position",
            description = "Stored as the driver's last known position (PostGIS geography + time) for the nearest-"
                    + "driver preference of seller pickups; an older fix never overwrites a newer one. Synced events "
                    + "with lat/lon update it too. Positions older than DRIVER_POSITION_STALE_AFTER (10 min) are "
                    + "ignored. 403 like /v1/driver/me without delivery:operate.")
    @PostMapping("/v1/driver/location")
    PositionView location(HttpServletRequest request, @Valid @RequestBody LocationRequest body) {
        return PositionView.of(locations.ping(new LocationPing(body.deviceId(), body.lat(), body.lon(),
                body.accuracyM(), body.capturedAt()), credentials.current(request)));
    }

    @Tag(name = "Dispatch")
    @Operation(operationId = "pickupDriverSuggestions", summary = "Nearest available drivers for a seller pickup",
            description = "Top 5: fresh position (KNN <-> on PostGIS), access active, an ASSIGNED or IN_PROGRESS "
                    + "route today in the pickup's delivery zone, not blocked, vehicle inspection passed, room left; "
                    + "ranked by geo road distance (straight line when geo is down). delivery:dispatch.")
    @GetMapping("/v1/dispatch/pickups/{id}/driver-suggestions")
    SuggestionsView suggestions(@PathVariable String id) {
        return new SuggestionsView(id, pickups.suggestions(id).stream().map(SuggestionView::of).toList());
    }

    @Tag(name = "Dispatch")
    @Operation(operationId = "pickupAssign", summary = "Override: put a seller pickup on a route",
            description = "From the pool, or from another route while it is not collected yet and its drop-off "
                    + "carries only this shipment (409 fulfilment.pickup-not-movable otherwise). The reason is logged "
                    + "(pickup assignments) and fulfilment.pickup.dispatch_assigned is published. delivery:dispatch.")
    @PostMapping("/v1/dispatch/pickups/{id}/assign")
    RouteView assign(@PathVariable String id, @Valid @RequestBody PickupAssignRequest body) {
        return RouteView.of(pickups.assign(id, body.routeId(), body.reason()));
    }

    @Tag(name = "Dispatch")
    @Operation(operationId = "pickupAssignments", summary = "Assignments of a seller pickup (auto and overrides)")
    @GetMapping("/v1/dispatch/pickups/{id}/assignments")
    List<AssignmentView> assignments(@PathVariable String id) {
        return pickups.history(id).stream().map(AssignmentView::of).toList();
    }
}
