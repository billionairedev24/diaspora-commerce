package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.taxreturns.domain.tax.ClassificationRule;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxCode;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Items known to be non-taxable (or exempt in the ship-to state) are priced at zero and never sent to the calculator. */
class TaxServiceImplTest {

    private final TaxCalculator calculator = mock(TaxCalculator.class);
    private final TaxClassificationRepository classification = mock(TaxClassificationRepository.class);
    private final TaxServiceImpl service;

    TaxServiceImplTest() {
        when(classification.codes()).thenReturn(List.of(
                new TaxCode("GENERAL_GOODS", "General", TaxCategory.GENERAL, "txcd_99999999", true),
                new TaxCode("CLOTHING", "Clothing", TaxCategory.GENERAL, "txcd_c", true),
                new TaxCode("NON_TAXABLE", "Not taxable", TaxCategory.GENERAL, null, true, false, Set.of()),
                new TaxCode("FRESH_PRODUCE", "Produce", TaxCategory.GROCERY, "txcd_p", true, true, Set.of("TX", "NJ"))));
        when(classification.rules()).thenReturn(List.of(
                new ClassificationRule(1L, "dress", null, null, "CLOTHING", null),
                new ClassificationRule(2L, "giftcard", null, null, "NON_TAXABLE", null),
                new ClassificationRule(3L, "grocery", "tax_class", "Fresh fruit or vegetables", "FRESH_PRODUCE", null)));
        ProductFacts facts = atins -> Map.of("ATPRODUCE", new ProductFacts.Facts("ATPRODUCE", "grocery", false,
                Map.of("tax_class", "Fresh fruit or vegetables")));
        service = new TaxServiceImpl(calculator, mock(TaxRateRepository.class), new TaxClassifierProvider(classification),
                facts, mock(Access.class));
    }

    private static TaxQuoteCommand.Line line(String id, String atin, String type) {
        return new TaxQuoteCommand.Line(id, atin, "org_a", null, type, 1, 1_000, 0, 0);
    }

    private static TaxQuote taxed(List<TaxLineInput> in) {
        return new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE, in.stream().map(l -> new TaxLineResult(l.lineId(),
                l.sellerOrgId(), l.category(), 1_000, new BigDecimal("0.0825"), 83, "US-TX", null,
                CollectedBy.MARKETPLACE, 0L, l.taxCode())).toList(), "STRIPE_TAX");
    }

    @Test
    void anOrderOfOnlyKnownNonTaxableItemsMakesNoProviderCall() {
        TaxQuote q = service.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "TX", "77002", null),
                List.of(line("1", "ATG", "giftcard"), line("2", "ATPRODUCE", null))));
        verify(calculator, never()).quote(any(), any(), any());
        assertThat(q.totalTaxMinor()).isZero();
        assertThat(q.provider()).isEqualTo("EXEMPT");
        assertThat(q.collectedBy()).isEqualTo(CollectedBy.NOT_COLLECTED);
        assertThat(q.lines()).extracting(TaxLineResult::taxCode).containsExactly("NON_TAXABLE", "FRESH_PRODUCE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheTaxableLinesAreSentAndTheOrderIsKept() {
        when(calculator.quote(any(), any(), any())).thenAnswer(i -> taxed(i.getArgument(2)));
        TaxQuote q = service.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "CA", "90001", null),
                List.of(line("1", "ATD", "dress"), line("2", "ATG", "giftcard"), line("3", "ATPRODUCE", null))));
        ArgumentCaptor<List<TaxLineInput>> sent = ArgumentCaptor.forClass(List.class);
        verify(calculator).quote(any(), any(), sent.capture());
        // the gift card is never sent; fresh produce is exempt only in TX and NJ, so in CA it is taxed
        assertThat(sent.getValue()).extracting(TaxLineInput::lineId).containsExactly("1", "3");
        assertThat(q.lines()).extracting(TaxLineResult::lineId).containsExactly("1", "2", "3");
        assertThat(q.lines()).extracting(TaxLineResult::taxMinor).containsExactly(83L, 0L, 83L);
        assertThat(q.totalTaxMinor()).isEqualTo(166);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exemptInTheShipToStateOnly() {
        when(calculator.quote(any(), any(), any())).thenAnswer(i -> taxed(i.getArgument(2)));
        service.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "NJ", "07001", null),
                List.of(line("1", "ATD", "dress"), line("3", "ATPRODUCE", null))));
        ArgumentCaptor<List<TaxLineInput>> sent = ArgumentCaptor.forClass(List.class);
        verify(calculator).quote(any(), any(), sent.capture());
        assertThat(sent.getValue()).extracting(TaxLineInput::lineId).containsExactly("1");
    }
}
