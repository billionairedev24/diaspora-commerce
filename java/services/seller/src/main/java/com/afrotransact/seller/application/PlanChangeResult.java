package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.PlanChange;

/** Whether a plan change applied now (upgrade, prorated) or at renewal (downgrade / cadence change). */
public record PlanChangeResult(PlanChange applied, BillingOverview billing) {
}
