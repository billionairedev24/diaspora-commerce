package com.afrotransact.taxreturns.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class StripeTaxCalculatorTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");

    private final RulesTaxCalculator rules = mock(RulesTaxCalculator.class);
    private final StripeTaxCalculator calculator = new StripeTaxCalculator(RestClient.builder(),
            new TaxReturnsProperties(new TaxReturnsProperties.Tax(TaxReturnsProperties.TaxProvider.STRIPE_TAX,
                    new TaxReturnsProperties.StripeTax("sk_test_x", "http://localhost:1", null)), null, null, null,
                    null), rules);

    private static TaxLineInput line(String id, String providerCode, long shipping) {
        return new TaxLineInput(id, "AT1", "org_a", TaxCategory.GENERAL, 2, 1_000, 200, shipping, "CLOTHING",
                providerCode);
    }

    @Test
    void sendsEachLineWithItsProviderCodeAndShippingAsItsOwnLine() {
        MultiValueMap<String, String> f = calculator.form(SHIP_TO, "USD",
                List.of(line("l1", "txcd_clothing", 599)));
        assertThat(f.getFirst("currency")).isEqualTo("usd");
        assertThat(f.getFirst("customer_details[address][postal_code]")).isEqualTo("77002");
        assertThat(f.getFirst("customer_details[address_source]")).isEqualTo("shipping");
        assertThat(f.getFirst("line_items[0][amount]")).isEqualTo("1800");
        assertThat(f.getFirst("line_items[0][tax_code]")).isEqualTo("txcd_clothing");
        assertThat(f.getFirst("line_items[0][reference]")).isEqualTo("l1");
        assertThat(f.getFirst("line_items[1][reference]")).isEqualTo("ship:l1");
        assertThat(f.getFirst("line_items[1][amount]")).isEqualTo("599");
        assertThat(f.getFirst("line_items[1][tax_code]")).isEqualTo("txcd_92010001");
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
                List.of(line("l1", "txcd_clothing", 599)));
        assertThat(q.provider()).isEqualTo("STRIPE_TAX");
        assertThat(q.collectedBy()).isEqualTo(CollectedBy.MARKETPLACE);
        assertThat(q.lines()).singleElement().satisfies(l -> {
            assertThat(l.taxMinor()).isEqualTo(197);
            assertThat(l.shippingTaxMinor()).isEqualTo(49);
            assertThat(l.rate()).isEqualByComparingTo("0.0825");
            assertThat(l.taxCode()).isEqualTo("CLOTHING");
            assertThat(l.jurisdiction()).isEqualTo("US-TX");
        });
    }

    @Test
    void aLineWithoutAProviderCodeIsQuotedByTheRulesEngineAndSaysSo() {
        List<TaxLineInput> lines = List.of(line("l1", null, 0));
        when(rules.quote(SHIP_TO, "USD", lines)).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE,
                List.of(), "RULES"));
        assertThat(calculator.quote(SHIP_TO, "USD", lines).provider()).isEqualTo("RULES-FALLBACK");
    }

    @Test
    void nonUsAddressesAreNeverSentToStripe() {
        TaxAddress canada = new TaxAddress("CA", "ON", "M5V", "Toronto");
        List<TaxLineInput> lines = List.of(line("l1", "txcd_clothing", 0));
        when(rules.quote(canada, "USD", lines)).thenReturn(new TaxQuote("USD", "CA", CollectedBy.NOT_COLLECTED,
                List.of(), "RULES"));
        assertThat(calculator.quote(canada, "USD", lines).provider()).isEqualTo("RULES");
    }
}
