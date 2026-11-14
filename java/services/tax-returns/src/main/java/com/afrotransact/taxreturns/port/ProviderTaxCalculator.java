package com.afrotransact.taxreturns.port;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.ProviderConnection;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;

/** A {@link TaxCalculator} over an external provider, which can also be asked strictly (no fallback) for SHADOW mode. */
public interface ProviderTaxCalculator extends TaxCalculator {

    TaxProviderId provider();

    /** Whether the provider can be asked at all (its credentials are set). */
    boolean configured();

    /**
     * Asks the provider for every line that carries its code and nothing else: no fallback to the rules engine.
     *
     * @throws TaxProviderException when the provider cannot answer
     */
    TaxQuote quoteStrict(TaxAddress shipTo, String currency, List<TaxLineInput> lines);

    /**
     * Asks the provider whether its credentials work (no tax is calculated, nothing is recorded). Never throws and
     * never returns a credential. Providers without such a call report that it is not available.
     */
    default ProviderConnection testConnection() {
        return ProviderConnection.unsupported(provider());
    }
}
