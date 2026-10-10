package com.afrotransact.seller.port;

import java.time.Instant;

/**
 * One organisation of the service directory: what other services (indexer, cart) need to label its listings.
 *
 * @param id          {@code org_…} id
 * @param displayName store name, or the legal name until a store name is set (null when neither is known yet)
 * @param kind        {@code SELLER} or {@code PROVIDER}
 * @param status      organisation status
 * @param updatedAt   last change
 */
public record OrgDirectoryEntry(String id, String displayName, String kind, String status, Instant updatedAt) {
}
