package com.afrotransact.identity.application;

import java.util.List;

/** Owners of a seller or provider organisation ({@code GET /v1/internal/orgs/{orgId}/owners}). */
public record OrgOwnersView(String orgId, List<String> ownerUserIds) {
}
