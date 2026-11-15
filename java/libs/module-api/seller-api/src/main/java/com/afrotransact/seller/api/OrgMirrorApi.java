package com.afrotransact.seller.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the identity module asks seller in-process to repair its copy of the organisations: approved or suspended
 * organisations page by page ({@code GET /v1/orgs/mirror}) and one organisation on demand ({@code GET /v1/orgs/{id}/mirror});
 * scope {@code sellers:directory}. Plain data calls (the token validation of identity's own endpoints is not involved). Entered
 * under the caller's in-process principal; a caller without the scope is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403.
 */
public interface OrgMirrorApi {

    /** One page of organisations whose status is one of {@code statuses} (names, for example APPROVED, SUSPENDED). */
    OrgMirrorPage page(int page, int size, List<String> statuses);

    /** @return the organisation as identity mirrors it, empty when it is unknown (HTTP 404) */
    Optional<OrgMirror> one(String orgId);

    record OrgMirror(String orgId, String kind, String primaryKind, String legalName, String displayName, String country,
                     String status, List<String> ownerUserIds, String applicantUserId, Instant occurredAt) {

        public OrgMirror {
            ownerUserIds = ownerUserIds == null ? List.of() : List.copyOf(ownerUserIds);
        }
    }

    record OrgMirrorPage(List<OrgMirror> items, long total) {

        public OrgMirrorPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
