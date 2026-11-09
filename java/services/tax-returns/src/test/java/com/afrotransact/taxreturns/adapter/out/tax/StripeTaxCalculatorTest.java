package com.afrotransact.taxreturns.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class StripeTaxCalculatorTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");

    private final InHouseTaxCalculator rules = mock(InHouseTaxCalculator.class);
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final StripeTaxCalculator calculator = calculator("sk_test_x");

    private StripeTaxCalculator calculator(String key) {
        return new StripeTaxCalculator(RestClient.builder(),
                new TaxReturnsProperties(new TaxReturnsProperties.Tax(TaxReturnsProperties.TaxProvider.STRIPE_TAX,
                        new TaxReturnsProperties.StripeTax(key, "http://localhost:1", null), null, null, null), null, null, null,
                        null),
                rules, telemetry);
    }

    /** A line whose outcome code maps to the given Stripe code (null: the code map has none for it). */
    private static TaxLineInput line(String id, String stripeCode, long shipping) {
        Map<String, String> codes = stripeCode == null ? Map.of() : Map.of("STRIPE", stripeCode);
        return new TaxLineInput(id, "AT1", "org_a", TaxCategory.GENERAL, 2, 1_000, 200, shipping,
                "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS", null, codes, false);
    }

    @Test
    void sendsEachLineWithTheCodeOfItsOutcomeAndShippingAsItsOwnLine() {
        MultiValueMap<String, String> f = calculator.form(SHIP_TO, "USD", List.of(line("l1", "txcd_40010002", 599)));
        assertThat(f.getFirst("currency")).isEqualTo("usd");
        assertThat(f.getFirst("customer_details[address][postal_code]")).isEqualTo("77002");
        assertThat(f.getFirst("customer_details[address_source]")).isEqualTo("shipping");
        assertThat(f.getFirst("line_items[0][amount]")).isEqualTo("1800");
        assertThat(f.getFirst("line_items[0][tax_code]")).isEqualTo("txcd_40010002");
        assertThat(f.getFirst("line_items[0][reference]")).isEqualTo("l1");
        assertThat(f.getFirst("line_items[1][reference]")).isEqualTo("ship:l1");
        assertThat(f.getFirst("line_items[1][amount]")).isEqualTo("599");
        assertThat(f.getFirst("line_items[1][tax_code]")).isEqualTo("txcd_92010001");
    }

    @Test
    void theSameStripeCodeOnDifferentOutcomesIsSentAsMappedForEach() {
        // txcd_41053001 carries four outcomes of one category: the map decides per outcome code, Stripe decides by its own rules
        TaxLineInput exempt = new TaxLineInput("a", "AT1", "o", TaxCategory.GENERAL, 1, 500, 0, 0,
                "AT.ENERGY_PROTEIN_DRINK_MIXES.EXEMPT_ONE", null, Map.of("STRIPE", "txcd_41053001"), true);
        TaxLineInput taxable = new TaxLineInput("b", "AT2", "o", TaxCategory.GENERAL, 1, 500, 0, 0,
                "AT.ENERGY_PROTEIN_DRINK_MIXES.TAXABLE_ONE", null, Map.of("STRIPE", "txcd_41053001"), false);
        MultiValueMap<String, String> f = calculator.form(SHIP_TO, "USD", List.of(exempt, taxable));
        assertThat(f.getFirst("line_items[0][tax_code]")).isEqualTo("txcd_41053001");
        assertThat(f.getFirst("line_items[1][tax_code]")).isEqualTo("txcd_41053001");
    }

    @Test
    void readsTaxPerLineAndFoldsShippingTaxIntoItsLine() {
        String body = """
                {"line_items":{"data":[
                  {"reference":"l1","amount_tax":148,"tax_breakdown":[{"tax_rate_details":{"percentage_decimal":"6.25"}},
                                                                      {"tax_rate_details":{"percentage_decimal":"2.00"}}]},
                  {"reference":"ship:l1","amount_tax":49,"tax_breakdown":[]}]}}
                """;
        TaxQuote q = calculator.parse(JsonMapper.builder().build().readTree(body), SHIP_TO, "USD",
                List.of(line("l1", "txcd_40010002", 599)));
        assertThat(q.provider()).isEqualTo("STRIPE_TAX");
        assertThat(q.collectedBy()).isEqualTo(CollectedBy.MARKETPLACE);
        assertThat(q.lines()).singleElement().satisfies(l -> {
            assertThat(l.taxMinor()).isEqualTo(197);
            assertThat(l.shippingTaxMinor()).isEqualTo(49);
            assertThat(l.rate()).isEqualByComparingTo("0.0825");
            assertThat(l.taxCode()).isEqualTo("AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS");
            assertThat(l.jurisdiction()).isEqualTo("US-TX");
            assertThat(l.addressPrecision()).isEqualTo("ADDRESS");
        });
    }

    @Test
    void aLineWithoutAProviderCodeFallsBackVisiblyAndListsTheLine() {
        List<TaxLineInput> lines = List.of(line("l1", null, 0), line("l2", "txcd_40010002", 0));
        when(rules.quote(SHIP_TO, "USD", lines)).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE,
                List.of(), "rules"));

        TaxQuote q = calculator.quote(SHIP_TO, "USD", lines);

        assertThat(q.provider()).isEqualTo("RULES-FALLBACK");
        assertThat(q.fallbackLines()).containsExactly("l1");
        verify(telemetry).providerFallback("STRIPE_TAX", "no-code", 1);
    }

    @Test
    void notBeingSetUpFallsBackVisiblyForEveryLine() {
        List<TaxLineInput> lines = List.of(line("l1", "txcd_40010002", 0));
        when(rules.quote(any(), any(), any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE,
                List.of(), "rules"));

        TaxQuote q = calculator("").quote(SHIP_TO, "USD", lines);

        assertThat(q.provider()).isEqualTo("RULES-FALLBACK");
        assertThat(q.fallbackLines()).containsExactly("l1");
        verify(telemetry).providerFallback("STRIPE_TAX", "not-configured", 1);
    }

    @Test
    void nonUsAddressesAreNeverSentToStripe() {
        TaxAddress canada = new TaxAddress("CA", "ON", "M5V", "Toronto");
        List<TaxLineInput> lines = List.of(line("l1", "txcd_40010002", 0));
        when(rules.quote(canada, "USD", lines)).thenReturn(new TaxQuote("USD", "CA", CollectedBy.NOT_COLLECTED,
                List.of(), "rules"));
        assertThat(calculator.quote(canada, "USD", lines).provider()).isEqualTo("RULES-FALLBACK");
    }

    @Test
    void theStrictQuoteNeverFallsBackSoShadowModeSeesStripesOwnAnswer() {
        assertThatThrownBy(() -> calculator.quoteStrict(SHIP_TO, "USD", List.of(line("l1", null, 0))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("No Stripe code");
        assertThatThrownBy(() -> calculator("").quoteStrict(SHIP_TO, "USD", List.of(line("l1", "txcd_40010002", 0))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("not configured");
        assertThat(calculator.provider()).isEqualTo(TaxProviderId.STRIPE);
    }
}
