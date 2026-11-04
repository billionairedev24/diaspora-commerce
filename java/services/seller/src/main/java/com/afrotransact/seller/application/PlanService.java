package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.OrgKind;

/** The plan catalogue as sellers see it, with prices per cadence and the trial (campaigns included). */
public interface PlanService {

    /** Public plans open to new organisations (onboarding, the public pricing page). */
    List<PlanOffer> list(OrgKind kind);

    /**
     * Plans an organisation may choose on its Billing page: public plans of its kind, invite-only plans it was invited
     * to, and its current plan even when hidden or retired (owner / finance, staff with seller:billing).
     */
    List<PlanOffer> listFor(String orgId);
}
