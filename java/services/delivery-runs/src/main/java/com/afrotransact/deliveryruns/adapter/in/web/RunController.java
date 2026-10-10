package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.CancelRequest;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.EconomicsPreviewRequest;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.ExtendRequest;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.RunOrderResponse;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.RunRequest;
import com.afrotransact.deliveryruns.adapter.in.web.RunDtos.RunResponse;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunEconomics;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Runs", description = "Staff: schedule runs and move them through their lifecycle (delivery-runs:manage)")
@RestController
class RunController {

    private final RunService runs;

    RunController(RunService runs) {
        this.runs = runs;
    }

    @Operation(summary = "Schedule a run (DRAFT); the response carries the route estimate and break-even orders")
    @PostMapping("/v1/runs")
    @ResponseStatus(HttpStatus.CREATED)
    RunResponse create(@Valid @RequestBody RunRequest body) {
        return withEconomics(runs.create(body.toCommand()));
    }

    @Operation(summary = "List runs, newest window first (delivery-runs:read)")
    @GetMapping("/v1/runs")
    List<RunResponse> list(@RequestParam(required = false) UUID locationId,
                           @RequestParam(required = false) Set<RunStatus> status,
                           @RequestParam(defaultValue = "100") int limit) {
        return runs.list(locationId, status, limit).stream().map(RunResponse::of).toList();
    }

    @Operation(summary = "Get a run with its break-even orders (delivery-runs:read)")
    @GetMapping("/v1/runs/{id}")
    RunResponse get(@PathVariable UUID id) {
        return withEconomics(runs.get(id));
    }

    @Operation(summary = "Reschedule a run (DRAFT / ANNOUNCED freely; OPEN: window, minimum, capacity, policy)")
    @PutMapping("/v1/runs/{id}")
    RunResponse update(@PathVariable UUID id, @Valid @RequestBody RunRequest body) {
        return RunResponse.of(runs.update(id, body.toCommand()));
    }

    @Operation(summary = "Delete a DRAFT run (others are cancelled instead)")
    @DeleteMapping("/v1/runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        runs.delete(id);
    }

    @Operation(summary = "DRAFT → ANNOUNCED (delivery-runs.run.announced)")
    @PostMapping("/v1/runs/{id}/announce")
    RunResponse announce(@PathVariable UUID id) {
        return RunResponse.of(runs.announce(id));
    }

    @Operation(summary = "DRAFT / ANNOUNCED → OPEN for preorders (delivery-runs.run.opened)")
    @PostMapping("/v1/runs/{id}/open")
    RunResponse open(@PathVariable UUID id) {
        return RunResponse.of(runs.open(id));
    }

    @Operation(summary = "Move the cutoff later (OPEN / EXTENDED / CUT_OFF → EXTENDED, delivery-runs.run.extended)")
    @PostMapping("/v1/runs/{id}/extend")
    RunResponse extend(@PathVariable UUID id, @Valid @RequestBody ExtendRequest body) {
        return RunResponse.of(runs.extend(id, body.toCommand()));
    }

    @Operation(summary = "Cancel; active slots are cancelled (delivery-runs.run.cancelled lists orders and customers)")
    @PostMapping("/v1/runs/{id}/cancel")
    RunResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest body) {
        return RunResponse.of(runs.cancel(id, body.reason()));
    }

    @Operation(summary = "Confirm now whatever the minimum (closes ordering if still open; delivery-runs.run.confirmed)")
    @PostMapping("/v1/runs/{id}/confirm")
    RunResponse confirm(@PathVariable UUID id) {
        return RunResponse.of(runs.confirm(id));
    }

    @Operation(summary = "CONFIRMED → COLLECTING (seller sweep started)")
    @PostMapping("/v1/runs/{id}/start-collecting")
    RunResponse startCollecting(@PathVariable UUID id) {
        return RunResponse.of(runs.startCollecting(id));
    }

    @Operation(summary = "COLLECTING → OUT_FOR_DELIVERY (delivery-runs.run.out_for_delivery)")
    @PostMapping("/v1/runs/{id}/out-for-delivery")
    RunResponse outForDelivery(@PathVariable UUID id) {
        return RunResponse.of(runs.outForDelivery(id));
    }

    @Operation(summary = "OUT_FOR_DELIVERY → COMPLETED (delivery-runs.run.completed)")
    @PostMapping("/v1/runs/{id}/complete")
    RunResponse complete(@PathVariable UUID id) {
        return RunResponse.of(runs.complete(id));
    }

    @Operation(summary = "Economics of a run being planned: route, cost, break-even, P&L (delivery-runs:read)",
            description = "Route from geo-service (origin → location centre); 502 when geo is unavailable.")
    @PostMapping("/v1/runs/economics-preview")
    RunEconomics preview(@Valid @RequestBody EconomicsPreviewRequest body) {
        return runs.preview(body.toCommand());
    }

    @Operation(summary = "P&L of a run at its reserved / confirmed orders (delivery-runs:read)",
            description = "commissionShareMinor: commission attributed to the run, reported by payments/analytics.")
    @GetMapping("/v1/runs/{id}/economics")
    RunEconomics economics(@PathVariable UUID id, @RequestParam(required = false) Long commissionShareMinor) {
        return runs.economics(id, commissionShareMinor);
    }

    @Operation(summary = "Orders on a run (delivery-runs:read)")
    @GetMapping("/v1/runs/{id}/orders")
    List<RunOrderResponse> orders(@PathVariable UUID id, @RequestParam(required = false) RunOrderStatus status) {
        return runs.orders(id, status).stream().map(RunOrderResponse::of).toList();
    }

    private RunResponse withEconomics(Run run) {
        if (run.estimate() == null) {
            return RunResponse.of(run);
        }
        try {
            return RunResponse.of(run, runs.economics(run.id(), null));
        } catch (GeoUnavailableException e) {
            return RunResponse.of(run);
        }
    }
}
