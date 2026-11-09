package com.afrotransact.taxreturns.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import org.junit.jupiter.api.Test;

class SwitchableTaxCalculatorTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");

    private final TaxMethodSource method = mock(TaxMethodSource.class);
    private final InHouseTaxCalculator rules = mock(InHouseTaxCalculator.class);
    private final ProviderTaxCalculator stripe = mock(ProviderTaxCalculator.class);
    private final ProviderTaxCalculator avalara = mock(ProviderTaxCalculator.class);
    private final TaxQuote ours = mock(TaxQuote.class);
    private final TaxQuote stripes = mock(TaxQuote.class);
    private final TaxQuote avalaras = mock(TaxQuote.class);
    private final SwitchableTaxCalculator calculator = new SwitchableTaxCalculator(method, rules,
            List.of(stripe, avalara));

    SwitchableTaxCalculatorTest() {
        when(stripe.provider()).thenReturn(TaxProviderId.STRIPE);
        when(avalara.provider()).thenReturn(TaxProviderId.AVALARA);
        when(rules.quote(any(), anyString(), any())).thenReturn(ours);
        when(stripe.quote(any(), anyString(), any())).thenReturn(stripes);
        when(avalara.quote(any(), anyString(), any())).thenReturn(avalaras);
    }

    @Test
    void ourOwnRulesAnswerByDefault() {
        when(method.method()).thenReturn(TaxMethod.RULES);
        List<TaxLineInput> none = List.of();

        assertThat(calculator.quote(SHIP_TO, "USD", none)).isSameAs(ours);
        verify(stripe, never()).quote(any(), anyString(), any());
        verify(avalara, never()).quote(any(), anyString(), any());
    }

    @Test
    void theChosenProviderAnswersAndHandlesItsOwnFallbackVisibly() {
        when(method.method()).thenReturn(TaxMethod.STRIPE_TAX);
        assertThat(calculator.quote(SHIP_TO, "USD", List.of())).isSameAs(stripes);

        when(method.method()).thenReturn(TaxMethod.AVALARA);
        assertThat(calculator.quote(SHIP_TO, "USD", List.of())).isSameAs(avalaras);
    }
}
