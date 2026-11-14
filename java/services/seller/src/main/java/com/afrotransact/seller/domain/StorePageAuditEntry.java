package com.afrotransact.seller.domain;

import java.time.Instant;

/** One line of the storefront's audit trail: who did what, when, to which draft version. */
public record StorePageAuditEntry(Instant at, String action, String actor, long version) {
}
