package com.afrotransact.inventory.adapter.in.web;

import java.util.List;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.CreateNodeRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.NodeResponse;
import com.afrotransact.inventory.application.NodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Nodes", description = "Fulfilment centres, origin hubs and warehouses")
@RestController
@RequestMapping("/v1/nodes")
class NodeController {

    private final NodeService nodes;

    NodeController(NodeService nodes) {
        this.nodes = nodes;
    }

    @Operation(summary = "List nodes (inventory:read)")
    @GetMapping
    List<NodeResponse> list() {
        return nodes.list().stream().map(NodeResponse::of).toList();
    }

    @Operation(summary = "Create a node (inventory:adjust)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    NodeResponse create(@Valid @RequestBody CreateNodeRequest body) {
        return NodeResponse.of(nodes.create(body.toDomain()));
    }
}
