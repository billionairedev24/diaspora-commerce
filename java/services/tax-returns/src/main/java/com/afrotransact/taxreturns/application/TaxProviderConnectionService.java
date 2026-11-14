package com.afrotransact.taxreturns.application;

import com.afrotransact.taxreturns.domain.tax.ProviderConnection;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/**
 * "Test connection" for a tax provider (tax:configure): asks the provider whether its credentials work, before shadow mode
 * or a cutover depends on them. Calculates nothing, records nothing, and the answer never carries a credential.
 */
public interface TaxProviderConnectionService {

    ProviderConnection test(TaxProviderId provider);
}
