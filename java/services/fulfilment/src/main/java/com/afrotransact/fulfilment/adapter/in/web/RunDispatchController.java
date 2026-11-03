package com.afrotransact.fulfilment.adapter.in.web;

import java.util.List;

import com.afrotransact.fulfilment.adapter.in.web.RunDispatchDtos.RunDispatchResponse;
import com.afrotransact.fulfilment.application.RunDispatchService;
import com.afrotransact.fulfilment.domain.RunDispatchStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dispatch", description = "Delivery dispatch console (delivery:dispatch): routes per zone, drivers, "
        + "reassignment of failed stops, SOS and defects, live driver events")
@RestController
class RunDispatchController {

    private final RunDispatchService runs;

    RunDispatchController(RunDispatchService runs) {
        this.runs = runs;
    }

    @Operation(operationId = "listRuns",
            summary = "Delivery runs in dispatch, latest delivery day first (fulfilment:operate or delivery:dispatch)",
            description = "A run appears when delivery-runs confirms it (a closed pre-order window's paid orders). Each "
                    + "carries its progress and its routes without their stops.")
    @GetMapping("/v1/dispatch/runs")
    List<RunDispatchResponse> list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        return runs.list(Enums.strict(RunDispatchStatus.class, status), limit).stream()
                .map(v -> RunDispatchResponse.of(v, false)).toList();
    }

    @Operation(operationId = "getRun",
            summary = "A run with its routes, stops (seller pickups before drops) and progress")
    @GetMapping("/v1/dispatch/runs/{runId}")
    RunDispatchResponse get(@PathVariable String runId) {
        return RunDispatchResponse.of(runs.get(runId), true);
    }

    @Operation(operationId = "planRun",
            summary = "Plan the run's routes again (delivery:dispatch)",
            description = "One route per run, split by capacity (RUN_ROUTE_CAPACITY bags), seller pickups before the "
                    + "drops; doorstep drops are ordered by road distance from the hub, pickup-point runs make one "
                    + "drop per order needing the customer's handover code. Only shipments that are not on a live "
                    + "route are planned, so planning twice is harmless. 502/503 when delivery-runs cannot be reached.")
    @PostMapping("/v1/dispatch/runs/{runId}/plan")
    RunDispatchResponse plan(@PathVariable String runId) {
        return RunDispatchResponse.of(runs.plan(runId), true);
    }
}
