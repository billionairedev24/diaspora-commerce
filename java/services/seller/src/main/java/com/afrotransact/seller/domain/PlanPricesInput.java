package com.afrotransact.seller.domain;

/** Staff input for a plan version: price per cadence in cents and the default trial. */
public record PlanPricesInput(Long monthlyCents, Long quarterlyCents, Long annualCents, Integer trialDays,
                              String note) {
}
