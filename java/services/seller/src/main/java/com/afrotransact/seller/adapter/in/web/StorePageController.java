package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.application.StorePageService;
import com.afrotransact.seller.application.StorePageService.StorePageView;
import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.domain.StorePageAuditEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Store page", description = "The seller's branded storefront (/b/{slug}): a draft the owner and admins "
        + "edit and publish. The JSON documents are validated by gateway-bff; this service stores them.")
@RestController
@RequestMapping("/v1/orgs/{orgId}/store-page")
class StorePageController {

    private final StorePageService service;
    private final JsonMapper json;

    StorePageController(StorePageService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @Schema(name = "StorePageSettings")
    record StorePageResponse(@Schema(description = "null until the first save") JsonNode draft,
                             @Schema(description = "null while unpublished") JsonNode published, boolean isPublished,
                             @Schema(description = "0 until the first save") long version, Long publishedVersion,
                             boolean hasUnpublishedChanges, Instant updatedAt, Instant publishedAt, boolean approved) {
    }

    @Schema(name = "StorePageDraftRequest")
    record DraftRequest(@NotNull JsonNode config, long version) {
    }

    @Schema(name = "StorePageVersionRequest")
    record VersionRequest(long version) {
    }

    @Schema(name = "StorePageAuditEntry")
    record AuditResponse(Instant at, String action, String actor, long version) {
    }

    @Operation(summary = "The storefront settings, draft and published")
    @GetMapping
    StorePageResponse get(@PathVariable String orgId) {
        return of(service.get(orgId));
    }

    @Operation(summary = "Save the draft", description = "409 seller.store-page-stale when version is not the stored "
            + "one (0 for the first save).")
    @PutMapping
    StorePageResponse save(@PathVariable String orgId, @Valid @RequestBody DraftRequest body) {
        return of(service.saveDraft(orgId, body.config().toString(), body.version()));
    }

    @Operation(summary = "Publish the draft", description = "422 seller.store-not-approved until the organisation is "
            + "approved; 409 when version is not the latest draft.")
    @PostMapping("/publish")
    StorePageResponse publish(@PathVariable String orgId, @RequestBody VersionRequest body) {
        return of(service.publish(orgId, body.version()));
    }

    @Operation(summary = "Take the page down")
    @PostMapping("/unpublish")
    StorePageResponse unpublish(@PathVariable String orgId) {
        return of(service.unpublish(orgId));
    }

    @Operation(summary = "Who saved, published and unpublished, newest first (last 50)")
    @GetMapping("/audit")
    List<AuditResponse> audit(@PathVariable String orgId) {
        List<StorePageAuditEntry> entries = service.audit(orgId);
        return entries.stream().map(e -> new AuditResponse(e.at(), e.action(), e.actor(), e.version())).toList();
    }

    private StorePageResponse of(StorePageView v) {
        StorePage p = v.page();
        if (p == null) {
            return new StorePageResponse(null, null, false, 0, null, false, null, null, v.approved());
        }
        return new StorePageResponse(node(p.draft()), node(p.published()), p.isPublished(), p.version(),
                p.publishedVersion(), p.hasUnpublishedChanges(), p.updatedAt(), p.publishedAt(), v.approved());
    }

    private JsonNode node(String text) {
        return text == null ? null : json.readTree(text);
    }
}
