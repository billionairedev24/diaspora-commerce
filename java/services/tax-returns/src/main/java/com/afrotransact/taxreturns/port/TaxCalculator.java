package com.afrotransact.taxreturns.port;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;

/**
 * Computes marketplace-facilitator sales tax. The default adapter runs the rules engine over the configured rate
 * table; an external provider (Stripe Tax, TaxJar, Avalara) can implement the same port.
 */
public interface TaxCalculator {

    TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines);
}
