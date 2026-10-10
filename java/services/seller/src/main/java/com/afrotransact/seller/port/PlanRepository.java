package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;

/** Plan catalogue (seeded) and the Stripe price lookup keys per cadence. */
public interface PlanRepository {

    List<Plan> findAll(OrgKind kind);

    Optional<Plan> find(String code);

    /** Stripe Price {@code lookup_key} for a plan and cadence, e.g. {@code seller_growth_quarterly}. */
    String stripeLookupKey(String planCode, Cadence cadence);

    /** Reverse of {@link #stripeLookupKey}: which plan and cadence a Stripe price lookup key belongs to. */
    Optional<PriceRef> byLookupKey(String lookupKey);

    /** A plan and cadence. */
    record PriceRef(String planCode, Cadence cadence) {
    }
}
