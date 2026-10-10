package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.PlacementDtos.PlacementRequest;
import com.afrotransact.content.adapter.in.web.PlacementDtos.PlacementResponse;
import com.afrotransact.content.adapter.in.web.PlacementDtos.RejectRequest;
import com.afrotransact.content.adapter.in.web.PlacementDtos.SlotResponse;
import com.afrotransact.content.application.PlacementService;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.SlotKey;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Placements", description = "Slots, placements and the review workflow (back office)")
@RestController
@RequestMapping("/v1")
class PlacementAdminController {

    private final PlacementService placements;

    PlacementAdminController(PlacementService placements) {
        this.placements = placements;
    }

    @Operation(summary = "Slot catalogue with payload schemas and size specs (content:placements.view)")
    @GetMapping("/slots")
    List<SlotResponse> slots() {
        return placements.slots().stream().map(SlotResponse::of).toList();
    }

    @Operation(summary = "List placements (content:placements.view)")
    @GetMapping("/placements")
    List<PlacementResponse> list(@RequestParam(required = false) PlacementStatus status,
                                 @RequestParam(required = false) String slot) {
        return placements.list(status, slot == null ? null : SlotKey.fromKey(slot)).stream()
                .map(PlacementResponse::of).toList();
    }

    @Operation(summary = "Get a placement (content:placements.view)")
    @GetMapping("/placements/{id}")
    PlacementResponse get(@PathVariable String id) {
        return PlacementResponse.of(placements.get(id));
    }

    @Operation(summary = "Create a draft; the payload is validated against the slot schema (content:placements.edit)")
    @PostMapping("/placements")
    @ResponseStatus(HttpStatus.CREATED)
    PlacementResponse create(@Valid @RequestBody PlacementRequest body) {
        return PlacementResponse.of(placements.create(SlotKey.fromKey(body.slot()), body.toContent()));
    }

    @Operation(summary = "Edit a draft (content:placements.edit); expectedVersion is required")
    @PutMapping("/placements/{id}")
    PlacementResponse update(@PathVariable String id, @Valid @RequestBody PlacementRequest body) {
        if (body.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        return PlacementResponse.of(placements.update(id, body.toContent(), body.expectedVersion()));
    }

    @Operation(summary = "Send a draft for review (content:placements.edit)")
    @PostMapping("/placements/{id}/submit")
    PlacementResponse submit(@PathVariable String id) {
        return PlacementResponse.of(placements.submit(id));
    }

    @Operation(summary = "Approve: SCHEDULED, or LIVE when the window has started. Paid creatives need a different "
            + "person than the author (content:placements.approve)")
    @PostMapping("/placements/{id}/approve")
    PlacementResponse approve(@PathVariable String id) {
        return PlacementResponse.of(placements.approve(id));
    }

    @Operation(summary = "Reject back to draft with a note (content:placements.approve)")
    @PostMapping("/placements/{id}/reject")
    PlacementResponse reject(@PathVariable String id, @Valid @RequestBody RejectRequest body) {
        return PlacementResponse.of(placements.reject(id, body.note()));
    }

    @Operation(summary = "End a scheduled or live placement now (content:placements.edit or approve)")
    @PostMapping("/placements/{id}/end")
    PlacementResponse end(@PathVariable String id) {
        return PlacementResponse.of(placements.end(id));
    }
}
