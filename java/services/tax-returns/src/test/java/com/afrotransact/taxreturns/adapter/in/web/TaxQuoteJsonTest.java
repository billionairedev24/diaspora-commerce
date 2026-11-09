package com.afrotransact.taxreturns.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.application.TaxQuoteCommand;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The quote's JSON: the decision fields on each line, no rate on a blocked line, the visible fallback and the new request fields. */
class TaxQuoteJsonTest {

    private final TaxService tax = mock(TaxService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaxController(tax, mock(TaxLedgerService.class)))
            .build();

    @Test
    void decidedAndBlockedLinesCarryTheContractsDecisionFields() throws Exception {
        TaxLineResult taxed = new TaxLineResult("1", "org", TaxCategory.GENERAL, 10_000, new BigDecimal("0.0825"), 825,
                "US-TX", "US-TX-770", CollectedBy.MARKETPLACE, 0L, "AT.X.TAXABLE", new LineDecision(ResolveStatus.RESOLVED,
                Outcome.TAXABLE, "AT.X.TAXABLE", Map.of("STRIPE", "txcd_1", "AVALARA", "PF1"), "tx-1", null, "x", "d"),
                "ZIP");
        TaxLineInput blockedIn = new TaxLineInput("2", "AT2", "org", TaxCategory.GENERAL, 1, 100, 0, 0);
        TaxLineResult blocked = TaxLineResult.blocked(blockedIn, "US-TX", LineDecision.blocked(
                ResolveStatus.MISSING_FACTS, "tx-1", null, "x"));
        when(tax.quote(any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE, List.of(taxed, blocked),
                "STRIPE_TAX", List.of()));

        mvc.perform(post("/v1/tax/quotes").contentType(MediaType.APPLICATION_JSON).content("""
                        {"currency":"USD","orderId":"order-1","saleDate":"2026-11-09",
                         "shipTo":{"state":"TX","postalCode":"77002"},
                         "lines":[{"lineId":"1","atin":"AT1","sellerOrgId":"org","quantity":1,"unitPriceMinor":10000,
                                   "discountMinor":0,"shippingMinor":0,"sellerBehaviour":{"sold_heated":false}},
                                  {"lineId":"2","atin":"AT2","sellerOrgId":"org","quantity":1,"unitPriceMinor":100,
                                   "discountMinor":0,"shippingMinor":0}]}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider", is("STRIPE_TAX")))
                .andExpect(jsonPath("$.lines[0].decisionStatus", is("RESOLVED")))
                .andExpect(jsonPath("$.lines[0].outcome", is("TAXABLE")))
                .andExpect(jsonPath("$.lines[0].internalTaxCode", is("AT.X.TAXABLE")))
                .andExpect(jsonPath("$.lines[0].providerTaxCode", is("txcd_1")))
                .andExpect(jsonPath("$.lines[0].ruleVersion", is("tx-1")))
                .andExpect(jsonPath("$.lines[0].addressPrecision", is("ZIP")))
                .andExpect(jsonPath("$.lines[1].decisionStatus", is("MISSING_FACTS")))
                .andExpect(jsonPath("$.lines[1].rate", nullValue()))
                .andExpect(jsonPath("$.lines[1].blockedReason", is(LineDecision.BLOCKED_MESSAGE)))
                .andExpect(jsonPath("$.lines[1].taxMinor", is(0)));

        ArgumentCaptor<TaxQuoteCommand> c = ArgumentCaptor.forClass(TaxQuoteCommand.class);
        verify(tax).quote(c.capture());
        assertThat(c.getValue().reference()).isEqualTo("order-1");
        assertThat(c.getValue().saleDate().toString()).isEqualTo("2026-11-09");
        assertThat(c.getValue().lines().getFirst().sellerBehaviour()).containsEntry("sold_heated", false);
        assertThat(c.getValue().lines().get(1).sellerBehaviour()).isEmpty();
    }

    @Test
    void aFallbackQuoteListsTheLinesTheProviderCouldNotTake() throws Exception {
        TaxLineResult line = new TaxLineResult("1", "org", TaxCategory.GENERAL, 10_000, new BigDecimal("0.0825"), 825,
                "US-TX", null, CollectedBy.MARKETPLACE);
        when(tax.quote(any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE, List.of(line),
                "rules").asFallback(List.of("1")));

        mvc.perform(post("/v1/tax/quotes").contentType(MediaType.APPLICATION_JSON).content("""
                        {"currency":"USD","shipTo":{"state":"TX","postalCode":"77002"},
                         "lines":[{"lineId":"1","taxCategory":"GENERAL","quantity":1,"unitPriceMinor":10000,
                                   "discountMinor":0,"shippingMinor":0}]}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider", is("RULES-FALLBACK")))
                .andExpect(jsonPath("$.fallbackLines", contains("1")))
                .andExpect(jsonPath("$.lines[0].decisionStatus", nullValue()));
    }
}
