package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.DispatchResponse;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.ReservationResponse;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.ReserveRequest;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.RunOrderResponse;
import com.afrotransact.deliveryruns.application.AudiencePage;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.application.SlotReservation;
import com.afrotransact.deliveryruns.application.SubscriptionService;
import com.afrotransact.deliveryruns.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service", description = "Service clients: orders (run slots), fulfilment (dispatch), notifications (audience)")
@RestController
class ServiceController {

    private final RunSlotService slots;
    private final SubscriptionService subscriptions;

    ServiceController(RunSlotService slots, SubscriptionService subscriptions) {
        this.slots = slots;
        this.subscriptions = subscriptions;
    }

    @Operation(summary = "Reserve a slot on a run for an order (delivery-runs:reserve)",
            description = "Idempotent per orderId (200 with the existing slot; 201 when created). A full or closed run "
                    + "moves the order to the location's next open run (`moved`) unless allowNextRun=false; 409 "
                    + "delivery-runs.run-full / run-closed when no run has room.")
    @PostMapping("/v1/service/runs/{runId}/reservations")
    ResponseEntity<ReservationResponse> reserve(@PathVariable UUID runId, @Valid @RequestBody ReserveRequest body) {
        SlotReservation r = slots.reserve(new ReserveCommand(runId, body.orderId(), body.customerId(), body.packages(),
                body.allowNextRun()));
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ReservationResponse.of(r));
    }

    @Operation(summary = "Release an order's slot (customer cancelled; idempotent) (delivery-runs:reserve)")
    @DeleteMapping("/v1/service/runs/{runId}/reservations/{orderId}")
    RunOrderResponse release(@PathVariable UUID runId, @PathVariable String orderId) {
        return RunOrderResponse.of(slots.release(runId, orderId));
    }

    @Operation(summary = "Confirmed orders of a confirmed run, with handover codes (delivery-runs:read)")
    @GetMapping("/v1/service/runs/{runId}/dispatch")
    DispatchResponse dispatch(@PathVariable UUID runId) {
        return DispatchResponse.of(slots.dispatch(runId));
    }

    @Operation(summary = "Runs confirmed for dispatch (CONFIRMED / COLLECTING), earliest window first")
    @GetMapping("/v1/service/runs/dispatchable")
    List<UUID> dispatchable() {
        return slots.dispatchable();
    }

    @Operation(summary = "Opted-in subscribers of a location on a channel (delivery-runs:audience.read)",
            description = "Keyset paging: pass `next` as `after`. For notifications' announcement fan-out.")
    @GetMapping("/v1/service/locations/{locationId}/audience")
    AudiencePage audience(@PathVariable UUID locationId, @RequestParam Channel channel,
                          @RequestParam(required = false) UUID after,
                          @RequestParam(defaultValue = "500") int limit) {
        return subscriptions.audience(locationId, channel, after, limit);
    }
}
