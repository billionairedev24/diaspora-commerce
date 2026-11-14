package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.ProviderConnection;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import org.junit.jupiter.api.Test;

/** "Test connection": staff with tax:configure only, delegated to the provider, nothing else happens. */
class TaxProviderConnectionServiceImplTest {

    private final ProviderTaxCalculator avalara = mock(ProviderTaxCalculator.class);
    private final ProviderTaxCalculator stripe = mock(ProviderTaxCalculator.class);

    TaxProviderConnectionServiceImplTest() {
        when(avalara.provider()).thenReturn(TaxProviderId.AVALARA);
        when(stripe.provider()).thenReturn(TaxProviderId.STRIPE);
        when(stripe.testConnection()).thenCallRealMethod();
    }

    private TaxProviderConnectionServiceImpl service(String permission) {
        return new TaxProviderConnectionServiceImpl(List.of(avalara, stripe), TestActors.staff(permission));
    }

    @Test
    void itAsksTheProviderAndReturnsWhatItFound() {
        ProviderConnection found = new ProviderConnection(TaxProviderId.AVALARA, true, true, "afro-sandbox", "sandbox",
                "Avalara accepted the credentials (sandbox)");
        when(avalara.testConnection()).thenReturn(found);

        assertThat(service("tax:configure").test(TaxProviderId.AVALARA)).isEqualTo(found);
    }

    @Test
    void aProviderWithoutAConnectionCheckSaysSo() {
        ProviderConnection c = service("tax:configure").test(TaxProviderId.STRIPE);

        assertThat(c.authenticated()).isFalse();
        assertThat(c.message()).contains("not available");
    }

    @Test
    void readingTaxIsNotEnoughToSpendACallOnTheProvider() {
        assertThatThrownBy(() -> service("tax:read").test(TaxProviderId.AVALARA)).isInstanceOf(RuntimeException.class);
        verify(avalara, never()).testConnection();
    }
}
