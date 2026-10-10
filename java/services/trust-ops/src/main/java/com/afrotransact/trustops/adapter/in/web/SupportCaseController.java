package com.afrotransact.trustops.adapter.in.web;

import java.util.UUID;

import com.afrotransact.trustops.adapter.in.web.CommonDtos.PageResponse;
import com.afrotransact.trustops.adapter.in.web.SupportCaseDtos.AssignCaseRequest;
import com.afrotransact.trustops.adapter.in.web.SupportCaseDtos.ResolveRequest;
import com.afrotransact.trustops.adapter.in.web.SupportCaseDtos.SupportCaseResponse;
import com.afrotransact.trustops.application.SupportCaseService;
import com.afrotransact.trustops.domain.SupportCaseStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Support cases", description = "Conversations Lola handed to human support (support:respond)")
@RestController
class SupportCaseController {

    private final SupportCaseService cases;

    SupportCaseController(SupportCaseService cases) {
        this.cases = cases;
    }

    @Operation(summary = "Support queue, soonest due first (default: not resolved)")
    @GetMapping("/v1/support-cases")
    PageResponse<SupportCaseResponse> queue(@RequestParam(required = false) SupportCaseStatus status,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return PageResponse.of(cases.queue(status, CommonDtos.page(page), CommonDtos.size(size)),
                SupportCaseResponse::of);
    }

    @Operation(summary = "One support case")
    @GetMapping("/v1/support-cases/{id}")
    SupportCaseResponse get(@PathVariable UUID id) {
        return SupportCaseResponse.of(cases.get(id));
    }

    @Operation(summary = "Assign (default: me)")
    @PostMapping("/v1/support-cases/{id}/assign")
    SupportCaseResponse assign(@PathVariable UUID id, @Valid @RequestBody(required = false) AssignCaseRequest body) {
        return SupportCaseResponse.of(cases.assign(id, body == null ? null : body.assignee()));
    }

    @Operation(summary = "Resolve")
    @PostMapping("/v1/support-cases/{id}/resolve")
    SupportCaseResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest body) {
        return SupportCaseResponse.of(cases.resolve(id, body.resolution()));
    }
}
