package com.afrotransact.bookings.application;

import com.afrotransact.bookings.domain.FeeSchedule;

/**
 * The fees payments applies to a provider's service bookings ({@code GET /v1/fee-schedules/effective?category=
 * services&orgId=…}): commission (override, category, default or none for AfroTransact Retail) and the card-fee
 * estimate. Answers are cached briefly; while payments cannot answer, the configured fees are used (and logged).
 */
public interface ProviderFees {

    /** Category of every bookable service in payments' fee schedule. */
    String CATEGORY = "services";

    FeeSchedule forOrg(String orgId);
}
