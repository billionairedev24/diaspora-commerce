package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.ProviderConnection;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import org.springframework.stereotype.Service;

@Service
class TaxProviderConnectionServiceImpl implements TaxProviderConnectionService {

    private final List<ProviderTaxCalculator> providers;
    private final Access access;

    TaxProviderConnectionServiceImpl(List<ProviderTaxCalculator> providers, Access access) {
        this.providers = providers;
        this.access = access;
    }

    @Override
    public ProviderConnection test(TaxProviderId provider) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        return providers.stream().filter(p -> p.provider() == provider).findFirst()
                .map(ProviderTaxCalculator::testConnection).orElseGet(() -> ProviderConnection.unsupported(provider));
    }
}
