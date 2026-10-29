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
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import org.junit.jupiter.api.Test;

class SwitchableTaxCalculatorTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");

    private final TaxMethodSource method = mock(TaxMethodSource.class);
    private final RulesTaxCalculator rules = mock(RulesTaxCalculator.class);
    private final StripeTaxCalculator stripe = mock(StripeTaxCalculator.class);
    private final TaxQuote ours = mock(TaxQuote.class);
    private final TaxQuote theirs = mock(TaxQuote.class);
    private final SwitchableTaxCalculator calculator = new SwitchableTaxCalculator(method, rules, stripe);

    SwitchableTaxCalculatorTest() {
        when(rules.quote(any(), anyString(), any())).thenReturn(ours);
        when(stripe.quote(any(), anyString(), any())).thenReturn(theirs);
    }

    @Test
    void ourOwnRulesAnswerByDefault() {
        when(method.method()).thenReturn(TaxMethod.RULES);
        List<TaxLineInput> none = List.of();

        assertThat(calculator.quote(SHIP_TO, "USD", none)).isSameAs(ours);
        verify(stripe, never()).quote(any(), anyString(), any());
    }

    @Test
    void stripeTaxAnswersOnlyWhenChosenAndSetUp() {
        when(method.method()).thenReturn(TaxMethod.STRIPE_TAX);
        when(stripe.configured()).thenReturn(true);
        assertThat(calculator.quote(SHIP_TO, "USD", List.of())).isSameAs(theirs);

        when(stripe.configured()).thenReturn(false);
        assertThat(calculator.quote(SHIP_TO, "USD", List.of())).isSameAs(ours);
    }
}
