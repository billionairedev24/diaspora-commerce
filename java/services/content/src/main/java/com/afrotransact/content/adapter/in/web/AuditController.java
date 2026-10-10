package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.application.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit", description = "Who changed which content, when")
@RestController
@RequestMapping("/v1/audit")
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @Schema(name = "ContentAuditEntry")
    record AuditDto(Instant at, String actor, String action, String entityType, String entityId,
                    Map<String, Object> detail) {
    }

    @Operation(summary = "Audit entries, newest first (content:audit.view)")
    @GetMapping
    List<AuditDto> find(@RequestParam(required = false) String entityType,
                        @RequestParam(required = false) String entityId,
                        @RequestParam(required = false, defaultValue = "100") int limit) {
        return audit.find(entityType, entityId, limit).stream()
                .map(e -> new AuditDto(e.at(), e.actor(), e.action(), e.entityType(), e.entityId(), e.detail()))
                .toList();
    }
}
