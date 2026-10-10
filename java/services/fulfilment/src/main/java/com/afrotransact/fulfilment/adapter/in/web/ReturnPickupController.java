package com.afrotransact.fulfilment.adapter.in.web;

import java.util.List;

import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.RouteView;
import com.afrotransact.fulfilment.adapter.in.web.ReturnPickupDtos.AddRequest;
import com.afrotransact.fulfilment.adapter.in.web.ReturnPickupDtos.CancelRequest;
import com.afrotransact.fulfilment.adapter.in.web.ReturnPickupDtos.ReturnPickupView;
import com.afrotransact.fulfilment.adapter.in.web.ReturnPickupDtos.ScheduleRequest;
import com.afrotransact.fulfilment.application.ReturnPickupService;
import com.afrotransact.fulfilment.domain.ReturnPickupStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Return pickups", description = "Customer returns collected by AfroTransact Delivery (RETURN_PICKUP stops)")
@RestController
class ReturnPickupController {

    private final ReturnPickupService pickups;

    ReturnPickupController(ReturnPickupService pickups) {
        this.pickups = pickups;
    }

    @Operation(operationId = "scheduleReturnPickup",
            summary = "Schedule a return pickup (SERVICE with delivery:returns, i.e. tax-returns; or dispatch)",
            description = "One pickup per RMA: a retry returns the existing pickup with 200. The pickup waits in the "
                    + "pool (REQUESTED) until dispatch adds it to a route as a RETURN_PICKUP stop; the driver checks "
                    + "the RMA's handover code with tax-returns (POST /v1/returns/{id}/pickup) and syncs "
                    + "return_pickup.collected or return_pickup.failed.")
    @PostMapping("/v1/return-pickups")
    ResponseEntity<ReturnPickupView> schedule(@Valid @RequestBody ScheduleRequest b) {
        ReturnPickupService.Result r = pickups.schedule(b.toCommand());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ReturnPickupView.of(r.pickup()));
    }

    @Operation(operationId = "getReturnPickup", summary = "One return pickup (tax-returns or dispatch)")
    @GetMapping("/v1/return-pickups/{id}")
    ReturnPickupView get(@PathVariable String id) {
        return ReturnPickupView.of(pickups.get(id));
    }

    @Operation(operationId = "listReturnPickups", summary = "Return pickups by status and depot (dispatch)")
    @GetMapping("/v1/return-pickups")
    List<ReturnPickupView> list(@RequestParam(required = false) ReturnPickupStatus status,
                                @RequestParam(required = false) String nodeId,
                                @RequestParam(defaultValue = "100") int limit) {
        return pickups.list(status, nodeId, limit).stream().map(ReturnPickupView::of).toList();
    }

    @Operation(operationId = "cancelReturnPickup",
            summary = "Cancel a return pickup (tax-returns when the RMA is cancelled, or dispatch)",
            description = "Cancels its RETURN_PICKUP stop unless the driver is already there (409). Idempotent.")
    @PostMapping("/v1/return-pickups/{id}/cancel")
    ReturnPickupView cancel(@PathVariable String id, @Valid @RequestBody(required = false) CancelRequest b) {
        return ReturnPickupView.of(pickups.cancel(id, b == null ? null : b.reason()));
    }

    @Operation(operationId = "addReturnPickupsToRoute",
            summary = "Add waiting return pickups to a route as RETURN_PICKUP stops (dispatch)")
    @PostMapping("/v1/dispatch/routes/{routeId}/return-pickups")
    RouteView addToRoute(@PathVariable String routeId, @Valid @RequestBody AddRequest b) {
        return RouteView.of(pickups.addToRoute(routeId, b.returnPickupIds()));
    }
}
