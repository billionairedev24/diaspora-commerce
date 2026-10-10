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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @Operation(operationId = "nodeAddress", summary = "Street address of a node (where sellers send FBA stock)")
    @GetMapping("/{id}/address")
    InboundDtos.AddressDto address(@PathVariable String id) {
        return InboundDtos.AddressDto.of(nodes.address(id));
    }

    @Operation(operationId = "updateNodeAddress", summary = "Set a node's street address (inventory:adjust)",
            description = "Inbound carrier labels need line 1, city and postal code; the country is the node's.")
    @PutMapping("/{id}/address")
    InboundDtos.AddressDto updateAddress(@PathVariable String id, @Valid @RequestBody InboundDtos.AddressDto body) {
        return InboundDtos.AddressDto.of(nodes.updateAddress(id, body.toDomain()));
    }

    @Operation(summary = "Create a node (inventory:adjust)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    NodeResponse create(@Valid @RequestBody CreateNodeRequest body) {
        return NodeResponse.of(nodes.create(body.toDomain()));
    }
}
