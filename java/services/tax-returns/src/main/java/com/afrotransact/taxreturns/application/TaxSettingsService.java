package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxMethodChange;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/**
 * Who calculates sales tax, as a switch staff can flip: our own rules (the default, always available), Stripe Tax or
 * Avalara (each only once it is set up), plus the SHADOW provider quoted next to the charging method for comparison. A
 * change needs a reason and is recorded; it applies to new quotes, never to orders already placed.
 */
public interface TaxSettingsService {

    /** The method and shadow provider in force, what staff may choose and the history (tax:read). */
    TaxSettings current();

    /**
     * Sets the method and the shadow provider together (tax:configure): a PUT replaces both, a null shadow provider
     * turns SHADOW off. A provider that is not configured cannot be chosen, a method that is a provider needs at least
     * one mapped provider code, and the shadow provider must differ from the method's provider.
     */
    TaxSettings change(TaxMethod method, TaxProviderId shadowProvider, String reason);

    /**
     * @param method            the method in force now
     * @param configuredDefault what applies until staff choose (the TAX_PROVIDER environment setting)
     * @param stripeAvailable   Stripe Tax can be chosen (its key is set)
     * @param avalaraAvailable  Avalara can be chosen (its account, license key and company code are set)
     * @param shadowProvider    the provider quoted in SHADOW mode, null = off
     */
    record TaxSettings(TaxMethod method, TaxMethod configuredDefault, boolean stripeAvailable,
                       boolean avalaraAvailable, TaxProviderId shadowProvider, String changedBy, Instant changedAt,
                       List<TaxMethodChange> history) {
    }
}
