package com.afrotransact.deliveryruns.application;

import java.util.Set;

import com.afrotransact.deliveryruns.domain.Fallback;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.Placement;

/**
 * Change the platform pre-order settings; null fields keep their current value.
 *
 * @param enabled    master switch
 * @param fallback   SHIP or WAITLIST
 * @param placements placements switched on
 * @param payment    fees, methods, cancellation, late-add and missed-delivery options (validated: fees 0–100.00,
 *                   at least one method)
 */
public record SettingsCommand(Boolean enabled, Fallback fallback, Set<Placement> placements,
                              PaymentSettings payment) {
}
