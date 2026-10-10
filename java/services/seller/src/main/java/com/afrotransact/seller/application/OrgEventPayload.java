package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.Organisation;

/**
 * Payload of seller.org.approved and seller.org.updated (indexer and cart keep display names from it; identity mirrors
 * the organisation and, on approval, grants the owner role to {@code applicantUserId} and {@code ownerUserIds}).
 *
 * @param kind            SELLER, PROVIDER, or BOTH when the organisation offers products and services (S215)
 * @param primaryKind     SELLER or PROVIDER: the portal it onboarded through, which fixes the owner role prefix
 * @param offering        PRODUCTS, SERVICES or BOTH (null before approval)
 * @param applicantUserId identity user id of the applicant whose application was approved; null on plain updates
 */
public record OrgEventPayload(String orgId, String kind, String primaryKind, String offering, String legalName,
                              String displayName, String country, String status, List<String> ownerUserIds,
                              String applicantUserId, Instant occurredAt) {

    static OrgEventPayload of(Organisation o, List<String> owners, Instant at) {
        return of(o, owners, null, at);
    }

    static OrgEventPayload of(Organisation o, List<String> owners, String applicantUserId, Instant at) {
        return new OrgEventPayload(o.id(), o.eventKind(), o.kind().name(),
                o.offering() == null ? null : o.offering().name(), o.legalName(), o.displayName(), o.country(),
                o.status().name(), owners, applicantUserId, at);
    }
}
