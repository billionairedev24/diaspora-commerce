package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxMethodChange;

/**
 * Who calculates sales tax, as a switch staff can flip: our own rules (the default, always available) or Stripe Tax
 * (only once it is set up). A change needs a reason and is recorded; it applies to new quotes, never to orders already
 * placed.
 */
public interface TaxSettingsService {

    /** The method in force, what staff may choose and the history (tax:read). */
    TaxSettings current();

    /** Switches the method (tax:configure). Stripe Tax is refused while it is not set up. */
    TaxSettings change(TaxMethod method, String reason);

    /**
     * @param method           the method in force now
     * @param configuredDefault what applies until staff choose (the TAX_PROVIDER environment setting)
     * @param stripeAvailable  Stripe Tax can be chosen (its key is set)
     */
    record TaxSettings(TaxMethod method, TaxMethod configuredDefault, boolean stripeAvailable, String changedBy,
                       Instant changedAt, List<TaxMethodChange> history) {
    }
}
