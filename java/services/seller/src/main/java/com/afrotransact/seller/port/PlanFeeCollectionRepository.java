package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.PlanFeeCollection;

/** The platform default for new sellers and the audit of every organisation's plan fee collection choice (S298). */
public interface PlanFeeCollectionRepository {

    /** How new sellers pay their plan unless they (or staff) choose otherwise. */
    PlatformDefault platformDefault();

    void setPlatformDefault(PlanFeeCollection mode, String actor, Instant at);

    void recordChange(Change change);

    /** Newest first. */
    List<Change> changes(String orgId, int limit);

    /** The platform default with who set it last. */
    record PlatformDefault(PlanFeeCollection mode, String updatedBy, Instant updatedAt) {
    }

    /**
     * One change of an organisation's choice.
     *
     * @param source      ONBOARDING, SELLER or STAFF
     * @param reasonCode  staff overrides only
     * @param appliesFrom the first invoice it applies to (the next renewal), when known
     */
    record Change(UUID id, String orgId, PlanFeeCollection previous, PlanFeeCollection mode, String source,
                  String actorId, String reasonCode, String note, Instant appliesFrom, Instant createdAt) {
    }
}
