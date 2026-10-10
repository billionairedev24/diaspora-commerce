package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.OrgSuspension;

/** Trust &amp; Safety suspension holds per organisation (at most one active). */
public interface OrgSuspensionRepository {

    OrgSuspension insert(OrgSuspension suspension);

    /** Optimistic update on {@code version}. */
    OrgSuspension update(OrgSuspension suspension);

    Optional<OrgSuspension> findActive(String orgId);

    /** Newest first. */
    List<OrgSuspension> history(String orgId, int limit);
}
