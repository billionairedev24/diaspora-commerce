package com.afrotransact.seller.application;

import java.time.Instant;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.PlanChange;

/**
 * What changing to {@code planCode} / {@code cadence} would do (S103), shown before the owner confirms: upgrades on the
 * same cadence apply now and invoice the proration now (none during a trial); downgrades and cadence changes apply at
 * the renewal.
 *
 * @param effectiveAt        now for an upgrade, the renewal for a scheduled change
 * @param amountDueNowCents  what is invoiced now: Stripe's proration preview (0 in a trial or for a scheduled change)
 * @param nextChargeCents    the plan's charge at the next renewal after the change
 */
public record PlanChangePreview(PlanChange change, String planCode, String planName, Cadence cadence,
                                Instant effectiveAt, long amountDueNowCents, long nextChargeCents,
                                Instant nextChargeAt, boolean trialing) {
}
