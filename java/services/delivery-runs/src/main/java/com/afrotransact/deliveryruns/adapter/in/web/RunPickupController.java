package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunPickListService;
import com.afrotransact.deliveryruns.application.RunPickLists;
import com.afrotransact.deliveryruns.application.RunProgressService;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.RunProgress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Batched seller pickups of a run (S264): pick lists for sellers and staff, and how far the run has got. */
@Tag(name = "Run pickups", description = "Seller pickup times and pick lists of pre-order runs, and run progress")
@RestController
class RunPickupController {

    private final RunPickListService pickLists;
    private final RunProgressService progress;

    RunPickupController(RunPickListService pickLists, RunProgressService progress) {
        this.pickLists = pickLists;
        this.progress = progress;
    }

    @Operation(operationId = "sellerRunPickups",
            summary = "The seller's pick lists: runs to pack for, with the pickup time (seller members: orders:read)",
            description = "A seller reads its own organisation; staff and services (delivery-runs:read) name `orgId`. "
                    + "Runs that are confirmed, being collected or out for delivery, and those completed in the last "
                    + "two weeks, earliest pickup first. Only the seller's own lines; no customer details.")
    @GetMapping("/v1/seller/run-pickups")
    RunPickListPage mine(@RequestParam(required = false) String orgId) {
        return new RunPickListPage(pickLists.forSeller(orgId).stream().map(RunPickListResponse::of).toList());
    }

    @Operation(operationId = "sellerRunPickup",
            summary = "The seller's pick list for one run (404 when nothing is collected from the organisation)")
    @GetMapping("/v1/seller/run-pickups/{runId}")
    RunPickListResponse mineForRun(@PathVariable UUID runId, @RequestParam(required = false) String orgId) {
        return RunPickListResponse.of(pickLists.forSeller(orgId, runId));
    }

    @Operation(operationId = "runPickLists",
            summary = "Every seller's pick list of a run, largest first (delivery-runs:read)")
    @GetMapping("/v1/runs/{id}/pick-lists")
    RunPickListPage forRun(@PathVariable UUID id) {
        return new RunPickListPage(pickLists.forRun(id).stream().map(RunPickListResponse::of).toList());
    }

    @Operation(operationId = "runProgress",
            summary = "How far the run has got: orders per stage, from the drivers' shipment events (delivery-runs:read)")
    @GetMapping("/v1/runs/{id}/progress")
    RunProgressResponse progress(@PathVariable UUID id) {
        return RunProgressResponse.of(id, progress.progress(id));
    }

    @Schema(name = "RunPickListPage")
    record RunPickListPage(List<RunPickListResponse> items) {
    }

    @Schema(name = "RunPickItem", description = "Units of one product")
    record RunPickItemResponse(String atin, String name, int units) {

        static RunPickItemResponse of(RunPickLists.Item i) {
            return new RunPickItemResponse(i.atin(), i.name(), i.units());
        }
    }

    @Schema(name = "RunOrderBag", description = "What goes into one order's bag; the order number is written on it")
    record RunOrderBagResponse(String orderId, String orderNumber,
                               @Schema(description = "PENDING until the driver collects the bag") String stage,
                               int units, List<RunPickItemResponse> lines) {

        static RunOrderBagResponse of(RunPickLists.OrderBag b) {
            return new RunOrderBagResponse(b.orderId(), b.orderNumber(), b.stage().name(), b.units(),
                    b.lines().stream().map(RunPickItemResponse::of).toList());
        }
    }

    @Schema(name = "RunPickList", description = "One seller's pick list for a run")
    record RunPickListResponse(UUID runId, String town, String state, String timeZone, String runStatus,
                               Instant windowStart, Instant windowEnd,
                               @Schema(description = "When the driver collects from the sellers") Instant sellerPickupAt,
                               DropMode dropMode, String sellerOrgId, String sellerName, int orders, int units,
                               int collectedOrders, List<RunPickItemResponse> items, List<RunOrderBagResponse> bags) {

        static RunPickListResponse of(RunPickLists.PickList p) {
            return new RunPickListResponse(p.runId(), p.town(), p.state(), p.timeZone(), p.runStatus(),
                    p.windowStart(), p.windowEnd(), p.sellerPickupAt(), p.dropMode(), p.sellerOrgId(),
                    p.sellerName(), p.orders(), p.units(), p.collectedOrders(),
                    p.items().stream().map(RunPickItemResponse::of).toList(),
                    p.bags().stream().map(RunOrderBagResponse::of).toList());
        }
    }

    @Schema(name = "RunProgress", description = "Orders per stage; derived from orders-service shipment events")
    record RunProgressResponse(UUID runId, int orders, int pending, int collected, int outForDelivery, int failed,
                               int delivered, int returned, Instant lastDeliveredAt) {

        static RunProgressResponse of(UUID runId, RunProgress p) {
            return new RunProgressResponse(runId, p.orders(), p.pending(), p.collected(), p.outForDelivery(),
                    p.failed(), p.delivered(), p.returned(), p.lastDeliveredAt());
        }
    }
}
