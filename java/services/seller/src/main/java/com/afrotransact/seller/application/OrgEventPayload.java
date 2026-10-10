package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.Organisation;

/** Payload of seller.org.approved and seller.org.updated (indexer and cart keep display names from it). */
public record OrgEventPayload(String orgId, String kind, String legalName, String displayName, String country,
                              String status, List<String> ownerUserIds, Instant occurredAt) {

    static OrgEventPayload of(Organisation o, List<String> owners, Instant at) {
        return new OrgEventPayload(o.id(), o.kind().name(), o.legalName(), o.displayName(), o.country(),
                o.status().name(), owners, at);
    }
}
