package com.afrotransact.fulfilment.adapter.in.web;

import java.util.List;

import com.afrotransact.fulfilment.adapter.in.web.CentreDtos.AssignNodeRequest;
import com.afrotransact.fulfilment.adapter.in.web.CentreDtos.CentreRequest;
import com.afrotransact.fulfilment.adapter.in.web.CentreDtos.CentreView;
import com.afrotransact.fulfilment.adapter.in.web.CentreDtos.PackRequest;
import com.afrotransact.fulfilment.adapter.in.web.CentreDtos.PickRequest;
import com.afrotransact.fulfilment.adapter.in.web.CentreDtos.TaskView;
import com.afrotransact.fulfilment.application.CentreService;
import com.afrotransact.fulfilment.application.TaskService;
import com.afrotransact.fulfilment.domain.TaskStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Fulfilment centres", description = "Centres / nodes and pick-pack tasks for FBA and Retail 1P shipments "
        + "(fulfilment:operate)")
@RestController
class CentreController {

    private final CentreService centres;
    private final TaskService tasks;

    CentreController(CentreService centres, TaskService tasks) {
        this.centres = centres;
        this.tasks = tasks;
    }

    @Operation(operationId = "listCentres",
            summary = "List fulfilment centres (fulfilment:operate or delivery:dispatch)")
    @GetMapping("/v1/centres")
    List<CentreView> list() {
        return centres.list().stream().map(CentreView::of).toList();
    }

    @Operation(operationId = "getCentre", summary = "Get a fulfilment centre")
    @GetMapping("/v1/centres/{id}")
    CentreView get(@PathVariable String id) {
        return CentreView.of(centres.get(id));
    }

    @Operation(operationId = "saveCentre", summary = "Create or update a fulfilment centre (fulfilment:operate)",
            description = "Use the inventory-service node id (e.g. HOU-FC1) so stock, tasks and routes line up.")
    @PutMapping("/v1/centres/{id}")
    CentreView save(@PathVariable String id, @Valid @RequestBody CentreRequest body) {
        return CentreView.of(centres.save(body.toDomain(id)));
    }

    @Operation(operationId = "listTasks", summary = "Pick/pack queue (fulfilment:operate)",
            description = "Tasks are created from orders.order.placed for FBA_AFROTRANSACT and RETAIL_1P shipments.")
    @GetMapping("/v1/fc/tasks")
    List<TaskView> tasks(@RequestParam(required = false) String nodeId,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "50") int limit) {
        return tasks.list(nodeId, Enums.strict(TaskStatus.class, status), limit).stream().map(TaskView::of).toList();
    }

    @Operation(operationId = "getTask", summary = "Get a task")
    @GetMapping("/v1/fc/tasks/{id}")
    TaskView task(@PathVariable String id) {
        return TaskView.of(tasks.get(id));
    }

    @Operation(operationId = "assignTaskNode",
            summary = "Assign the fulfilment centre (when inventory had no reservation node)")
    @PostMapping("/v1/fc/tasks/{id}/node")
    TaskView assignNode(@PathVariable String id, @Valid @RequestBody AssignNodeRequest body) {
        return TaskView.of(tasks.assignNode(id, body.nodeId()));
    }

    @Operation(operationId = "startPick",
            summary = "Start picking", description = "Moves the shipment to confirmed in orders-service.")
    @PostMapping("/v1/fc/tasks/{id}/pick/start")
    TaskView startPick(@PathVariable String id) {
        return TaskView.of(tasks.startPick(id));
    }

    @Operation(operationId = "recordPick", summary = "Record picked units",
            description = "All units ⇒ PICKED; fewer with shortReason ⇒ PICK_EXCEPTION (restart the pick to retry).")
    @PostMapping("/v1/fc/tasks/{id}/pick")
    TaskView pick(@PathVariable String id, @Valid @RequestBody PickRequest body) {
        return TaskView.of(tasks.completePick(id, body.picked(), body.lots(), body.shortReason()));
    }

    @Operation(operationId = "packTask", summary = "Pack the parcel",
            description = "Moves the shipment to packed in orders-service. Carrier shipments attach the carrier "
                    + "label (bought from carrier-gateway POST /v1/labels) for tracking.")
    @PostMapping("/v1/fc/tasks/{id}/pack")
    TaskView pack(@PathVariable String id, @Valid @RequestBody PackRequest body) {
        return TaskView.of(tasks.pack(id, body.weightLb(), body.carrier(), body.trackingNumber()));
    }

    @Operation(operationId = "stageTask",
            summary = "Stage at the outbound dock", description = "Moves the shipment to ready in orders-service.")
    @PostMapping("/v1/fc/tasks/{id}/ready")
    TaskView ready(@PathVariable String id) {
        return TaskView.of(tasks.ready(id));
    }
}
