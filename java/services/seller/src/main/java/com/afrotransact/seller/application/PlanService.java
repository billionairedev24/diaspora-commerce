package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.OrgKind;

/** Public plan catalogue with computed prices. */
public interface PlanService {

    List<PlanOffer> list(OrgKind kind);
}
