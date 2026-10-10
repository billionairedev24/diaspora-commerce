package com.afrotransact.trustops.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.afrotransact.trustops.adapter.in.web.AuditDtos.AuditEntryResponse;
import com.afrotransact.trustops.adapter.in.web.AuditDtos.VerificationResponse;
import com.afrotransact.trustops.adapter.in.web.CommonDtos.PageResponse;
import com.afrotransact.trustops.application.AuditLogService;
import com.afrotransact.trustops.port.AuditQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit log", description = "Immutable, hash-chained record of every privileged action (audit:read)")
@RestController
class AuditController {

    private final AuditLogService audit;

    AuditController(AuditLogService audit) {
        this.audit = audit;
    }

    @Operation(summary = "Search the audit log, newest first: filter by actor, entity, action, org and date [from, to)")
    @GetMapping("/v1/audit/entries")
    PageResponse<AuditEntryResponse> search(@RequestParam(required = false) String actorId,
                                            @RequestParam(required = false) String entityType,
                                            @RequestParam(required = false) String entityId,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String orgId,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return PageResponse.of(audit.search(new AuditQuery(actorId, entityType, entityId, action, orgId, from, to),
                CommonDtos.page(page), CommonDtos.size(size)), AuditEntryResponse::of);
    }

    @Operation(summary = "One audit entry by sequence number")
    @GetMapping("/v1/audit/entries/{seq}")
    AuditEntryResponse get(@PathVariable long seq) {
        return AuditEntryResponse.of(audit.get(seq));
    }

    @Operation(summary = "CSV export of matching entries, oldest first (same filters; capped)",
            responses = @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv",
                    schema = @Schema(type = "string"))))
    @GetMapping(value = "/v1/audit/export.csv", produces = "text/csv")
    void export(@RequestParam(required = false) String actorId,
                @RequestParam(required = false) String entityType,
                @RequestParam(required = false) String entityId,
                @RequestParam(required = false) String action,
                @RequestParam(required = false) String orgId,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"");
        Writer out = response.getWriter();
        out.write(AuditCsv.HEADER);
        audit.export(new AuditQuery(actorId, entityType, entityId, action, orgId, from, to), e -> {
            try {
                out.write(AuditCsv.row(e));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        out.flush();
    }

    @Operation(summary = "Verify the hash chain (default: the whole log); detects edited, re-hashed or missing entries")
    @GetMapping("/v1/audit/verify")
    VerificationResponse verify(@RequestParam(required = false) Long fromSeq,
                                @RequestParam(required = false) Long toSeq) {
        return VerificationResponse.of(audit.verify(fromSeq, toSeq));
    }
}
