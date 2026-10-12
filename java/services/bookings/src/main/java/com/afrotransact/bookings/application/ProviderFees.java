package com.afrotransact.bookings.application;

import com.afrotransact.bookings.domain.FeeSchedule;

/**
 * The fees payments applies to a provider's service bookings ({@code GET /v1/fee-schedules/effective?kind=SERVICE&
 * category=<service category>&orgId=…}): the connection fee (negotiated, plan, category, default, or none for
 * AfroTransact Retail) and the card-fee estimate. Answers are cached briefly per organisation and category; while
 * payments cannot answer, the configured fees are used (and logged).
 */
public interface ProviderFees {

    /** Category of services without one (and of bookings made before the category was recorded). */
    String CATEGORY = "services";

    /** Fees of the organisation's bookings in {@code category} (null or blank = {@link #CATEGORY}). */
    FeeSchedule forOrg(String orgId, String category);

    /** Fees of a service without a category. */
    default FeeSchedule forOrg(String orgId) {
        return forOrg(orgId, CATEGORY);
    }
}
