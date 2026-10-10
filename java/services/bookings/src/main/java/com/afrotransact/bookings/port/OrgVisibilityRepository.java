package com.afrotransact.bookings.port;

import java.time.Instant;

/** Whether an organisation's services are hidden from the storefront (seller compliance enforcement). */
public interface OrgVisibilityRepository {

    boolean isHidden(String orgId);

    /**
     * Records hidden / restored as of {@code occurredAt}; an older change than the stored one is ignored.
     *
     * @return true when the stored visibility changed
     */
    boolean apply(String orgId, boolean hidden, String reason, Instant occurredAt);
}
