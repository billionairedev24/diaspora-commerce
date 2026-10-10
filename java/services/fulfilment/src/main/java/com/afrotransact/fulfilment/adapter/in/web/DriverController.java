package com.afrotransact.fulfilment.adapter.in.web;

import java.util.List;

import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.RouteView;
import com.afrotransact.fulfilment.adapter.in.web.DriverDtos.SessionView;
import com.afrotransact.fulfilment.adapter.in.web.DriverDtos.SosRequest;
import com.afrotransact.fulfilment.adapter.in.web.DriverDtos.SyncRequest;
import com.afrotransact.fulfilment.application.DriverAccessService;
import com.afrotransact.fulfilment.application.DriverQueryService;
import com.afrotransact.fulfilment.application.DriverSession;
import com.afrotransact.fulfilment.application.DriverSyncService;
import com.afrotransact.fulfilment.application.EventResult;
import com.afrotransact.fulfilment.application.SyncResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Driver app", description = "Offline-first API of the AfroTransact Delivery driver app (delivery:operate)")
@RestController
class DriverController {

    private final DriverAccessService access;
    private final DriverQueryService queries;
    private final DriverSyncService sync;
    private final Credentials credentials;
    private final SseHub hub;

    DriverController(DriverAccessService access, DriverQueryService queries, DriverSyncService sync,
                     Credentials credentials, SseHub hub) {
        this.access = access;
        this.queries = queries;
        this.sync = sync;
        this.credentials = credentials;
        this.hub = hub;
    }

    @Operation(operationId = "driverMe", summary = "Access check after sign-in",
            description = "200 with the driver's open routes when the token grants delivery:operate. 403 "
                    + "fulfilment.no-driver-access (show “No driver access” with grantHint) or "
                    + "fulfilment.driver-access-revoked (signOut=true: sign out, keep the outbox and sync it).")
    @GetMapping("/v1/driver/me")
    SessionView me(HttpServletRequest request) {
        DriverSession s = access.check(credentials.current(request));
        return new SessionView(s.driverId(), s.access(), s.permissions(), s.routeIds(), s.checkedAt());
    }

    @Operation(operationId = "driverRoutes",
            summary = "My open routes with stops, packages and manifest (download for offline work)")
    @GetMapping("/v1/driver/routes")
    List<RouteView> routes(HttpServletRequest request) {
        return queries.myRoutes(credentials.current(request)).stream().map(RouteView::of).toList();
    }

    @Operation(operationId = "driverRoute", summary = "One of my routes")
    @GetMapping("/v1/driver/routes/{routeId}")
    RouteView route(HttpServletRequest request, @PathVariable String routeId) {
        return RouteView.of(queries.myRoute(credentials.current(request), routeId));
    }

    @Operation(operationId = "driverSync", summary = "Sync queued events (offline-first)",
            description = "Events are applied strictly in per-device sequence order, each at most once (eventId) and "
                    + "in its own transaction. Per event: APPLIED, REJECTED (a rule failed; fix and queue a new "
                    + "event), CONFLICT (state moved on / sequence reused) or DEFERRED (an earlier sequence is "
                    + "missing; resend from expectedSequence). Replays return the stored outcome with replayed=true. "
                    + "SOS events are raised first even behind a gap. After revocation signOut=true and only events "
                    + "captured before revokedAt are accepted.")
    @PostMapping("/v1/driver/sync")
    SyncResult sync(HttpServletRequest request, @Valid @RequestBody SyncRequest body) {
        return sync.sync(body.deviceId(), body.events().stream().map(DriverDtos.DriverEventRequest::toEvent)
                .toList(), credentials.current(request));
    }

    @Operation(operationId = "driverSos",
            summary = "Raise an SOS now (also keep it in the outbox with the same eventId)",
            description = "Dispatch is alerted immediately: fulfilment.driver.sos_raised (priority HIGH) and "
                    + "driver.sos on the dispatch stream. Idempotent per eventId.")
    @PostMapping("/v1/driver/sos")
    EventResult sos(HttpServletRequest request, @Valid @RequestBody SosRequest body) {
        return sync.sos(body.deviceId(), body.event().toEvent(), credentials.current(request));
    }

    @Operation(operationId = "driverStream", summary = "My live stream (server-sent events)",
            description = "session.revoked (sign out now), route.assigned, route.updated, route.unassigned, "
                    + "route.cancelled, route.unblocked, vehicle.defect, driver.sos.")
    @GetMapping(path = "/v1/driver/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(HttpServletRequest request) {
        return hub.driver(queries.driverId(credentials.current(request)));
    }
}
