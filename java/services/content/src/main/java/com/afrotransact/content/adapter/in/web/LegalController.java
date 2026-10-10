package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.LegalDtos.AmendRequest;
import com.afrotransact.content.adapter.in.web.LegalDtos.IndexResponse;
import com.afrotransact.content.adapter.in.web.LegalDtos.PageResponse;
import com.afrotransact.content.adapter.in.web.LegalDtos.RaiseRequest;
import com.afrotransact.content.adapter.in.web.LegalDtos.SummaryDto;
import com.afrotransact.content.adapter.in.web.LegalDtos.VersionDto;
import com.afrotransact.content.adapter.in.web.LegalDtos.VersionResponse;
import com.afrotransact.content.application.ContentSettings;
import com.afrotransact.content.application.LegalService;
import com.afrotransact.content.domain.legal.LegalDocument.NewVersion;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Legal", description = "Legal & privacy center: current documents, versions, counsel sign-off")
@RestController
@RequestMapping("/v1/legal")
class LegalController {

    private final LegalService legal;
    private final ContentSettings settings;

    LegalController(LegalService legal, ContentSettings settings) {
        this.legal = legal;
        this.settings = settings;
    }

    @Operation(summary = "Current version of every document")
    @SecurityRequirements
    @GetMapping
    ResponseEntity<IndexResponse> index() {
        return cached(new IndexResponse(legal.summaries().stream().map(SummaryDto::of).toList()));
    }

    @Operation(summary = "A document's current version, rendered (placeholders filled from the site config)")
    @SecurityRequirements
    @GetMapping("/{type}")
    ResponseEntity<PageResponse> current(@PathVariable @Parameter(example = "sms-terms") String type,
                                         @RequestParam(required = false, defaultValue = "en") String locale) {
        return cached(PageResponse.of(legal.current(LegalDocumentType.fromKey(type), locale)));
    }

    @Operation(summary = "All versions of a document, newest first (archived ones stay retrievable)")
    @SecurityRequirements
    @GetMapping("/{type}/versions")
    ResponseEntity<List<VersionDto>> versions(@PathVariable String type) {
        return cached(legal.history(LegalDocumentType.fromKey(type)).stream().map(VersionDto::of).toList());
    }

    @Operation(summary = "One version of a document (current or archived)")
    @SecurityRequirements
    @GetMapping("/{type}/versions/{version}")
    ResponseEntity<PageResponse> version(@PathVariable String type, @PathVariable String version,
                                         @RequestParam(required = false, defaultValue = "en") String locale) {
        return cached(PageResponse.of(legal.version(LegalDocumentType.fromKey(type), version, locale)));
    }

    @Operation(summary = "Raise the version; the previous version is archived (content:legal.edit)")
    @PostMapping("/{type}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    VersionResponse raise(@PathVariable String type, @Valid @RequestBody RaiseRequest body) {
        return VersionResponse.of(legal.raise(LegalDocumentType.fromKey(type), new NewVersion(body.version(),
                body.effectiveDate(), body.draftBanner() == null || body.draftBanner(), body.bodies(),
                body.changeSummary())));
    }

    @Operation(summary = "Amend the current version: counsel sign-off, effective date, translations (content:legal.edit)")
    @PatchMapping("/{type}/current")
    VersionResponse amend(@PathVariable String type, @RequestBody AmendRequest body) {
        return VersionResponse.of(legal.amend(LegalDocumentType.fromKey(type), body.draftBanner(),
                body.effectiveDate(), body.bodies()));
    }

    private <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(settings.siteMaxAge()).cachePublic()).body(body);
    }
}
