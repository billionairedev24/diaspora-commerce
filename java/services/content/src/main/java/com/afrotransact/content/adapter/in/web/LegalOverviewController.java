package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.LegalAuthoringDtos.OverviewEntry;
import com.afrotransact.content.application.LegalAuthoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Legal", description = "Legal & privacy center: current documents, versions, counsel sign-off")
@RestController
@RequestMapping("/v1/legal")
class LegalOverviewController {

    private final LegalAuthoringService authoring;

    LegalOverviewController(LegalAuthoringService authoring) {
        this.authoring = authoring;
    }

    @Operation(operationId = "getLegalOverview", summary = "Every document's current version, who published it and its draft (content:legal.view)")
    @GetMapping("/overview")
    List<OverviewEntry> overview() {
        return authoring.overview().stream().map(OverviewEntry::of).toList();
    }
}
