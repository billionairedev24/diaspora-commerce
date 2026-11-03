package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.OrgDirectoryService;
import com.afrotransact.seller.application.OrgEventPayload;
import com.afrotransact.seller.application.OrgMirrorService;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.port.OrgDirectoryEntry;
import com.afrotransact.seller.port.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** S213: the organisation directory for service clients (indexer warm-up). */
@Tag(name = "Organisations", description = "Seller and provider organisations")
@RestController
class OrgDirectoryController {

    private final OrgDirectoryService directory;
    private final OrgMirrorService mirror;

    OrgDirectoryController(OrgDirectoryService directory, OrgMirrorService mirror) {
        this.directory = directory;
        this.mirror = mirror;
    }

    @Operation(summary = "Paged organisation directory (id, display name, kind, status) for service clients",
            description = "Ordered by id. displayName falls back to the legal name until a store name is set. Needs "
                    + "sellers:directory: the indexer-service client-credentials client (seller-name warm-up), or staff.")
    @GetMapping("/v1/orgs/directory")
    OrgDirectoryPage orgDirectory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size,
            @Parameter(description = "Comma-separated statuses (default: all)") @RequestParam(required = false)
            List<String> status) {
        return OrgDirectoryPage.of(directory.directory(page, size, statuses(status)));
    }

    @Operation(summary = "Organisations as identity mirrors them (status, kind, names, owners, approved applicant)",
            description = "Ordered by id; the same shape as seller.org.approved / seller.org.updated. Identity's "
                    + "reconcile job reads it to repair its copy when an event was missed or arrived late. Needs "
                    + "sellers:directory as a service client, or staff.")
    @GetMapping("/v1/orgs/mirror")
    OrgMirrorPage orgMirror(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size,
            @Parameter(description = "Comma-separated statuses (default: all)") @RequestParam(required = false)
            List<String> status) {
        Page<OrgEventPayload> p = mirror.page(page, size, statuses(status));
        return new OrgMirrorPage(p.items(), p.page(), p.size(), p.total());
    }

    @Operation(summary = "One organisation as identity mirrors it",
            description = "Identity syncs one organisation on demand (gateway-bff saw the approval before identity "
                    + "did). Needs sellers:directory as a service client, or staff.")
    @GetMapping("/v1/orgs/{id}/mirror")
    OrgEventPayload orgMirrorOne(@PathVariable String id) {
        return mirror.one(id).orElseThrow(() -> new NotFoundException("seller.org-not-found",
                "Organisation " + id + " not found"));
    }

    private static Set<OrgStatus> statuses(List<String> status) {
        Set<OrgStatus> statuses = EnumSet.noneOf(OrgStatus.class);
        if (status != null) {
            for (String s : status) {
                if (!s.isBlank()) {
                    statuses.add(OrgStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                }
            }
        }
        return statuses;
    }

    @Schema(name = "OrgMirrorPage")
    record OrgMirrorPage(List<OrgEventPayload> items, int page, int size, long total) {
    }

    @Schema(name = "OrgDirectoryEntry")
    record Entry(String id, String displayName, String kind, String status, Instant updatedAt) {
    }

    @Schema(name = "OrgDirectoryPage")
    record OrgDirectoryPage(List<Entry> items, int page, int size, long total) {

        static OrgDirectoryPage of(Page<OrgDirectoryEntry> p) {
            return new OrgDirectoryPage(p.items().stream()
                    .map(e -> new Entry(e.id(), e.displayName(), e.kind(), e.status(), e.updatedAt())).toList(),
                    p.page(), p.size(), p.total());
        }
    }
}
