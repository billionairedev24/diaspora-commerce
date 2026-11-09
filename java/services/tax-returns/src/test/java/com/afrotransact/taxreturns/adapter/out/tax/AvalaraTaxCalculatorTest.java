package com.afrotransact.taxreturns.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** The Avalara skeleton: config keys, request mapping, and a clear "not configured / not implemented" answer. */
class AvalaraTaxCalculatorTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    private final InHouseTaxCalculator rules = mock(InHouseTaxCalculator.class);
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);

    private AvalaraTaxCalculator calculator(boolean configured) {
        TaxReturnsProperties.AvalaraTax cfg = configured
                ? new TaxReturnsProperties.AvalaraTax("123", "key", "AFROTRANSACT", null, null)
                : new TaxReturnsProperties.AvalaraTax(null, null, null, null, null);
        return new AvalaraTaxCalculator(new TaxReturnsProperties(new TaxReturnsProperties.Tax(null, null, cfg, null, null),
                null, null, null, null), rules, telemetry, CLOCK);
    }

    private static TaxLineInput line(String id, String code) {
        return new TaxLineInput(id, "AT" + id, "org", TaxCategory.GENERAL, 2, 1_000, 200, 0, "AT.X.Y", null,
                code == null ? Map.of() : Map.of("AVALARA", code), false);
    }

    @Test
    void configurationNeedsAccountLicenseKeyAndCompanyCode() {
        assertThat(calculator(false).configured()).isFalse();
        assertThat(calculator(true).configured()).isTrue();
        assertThat(calculator(true).provider()).isEqualTo(TaxProviderId.AVALARA);
        assertThat(new TaxReturnsProperties.AvalaraTax("1", "k", " ", null, null).configured()).isFalse();
        assertThat(new TaxReturnsProperties.AvalaraTax(null, null, null, null, null).baseUrl())
                .isEqualTo("https://sandbox-rest.avatax.com");
    }

    @Test
    void theStrictQuoteSaysClearlyWhyItCannotAnswer() {
        assertThatThrownBy(() -> calculator(false).quoteStrict(SHIP_TO, "USD", List.of(line("1", "PF050000"))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("not configured")
                .hasMessageContaining("AVALARA_ACCOUNT_ID");
        assertThatThrownBy(() -> calculator(true).quoteStrict(SHIP_TO, "USD", List.of(line("1", "PF050000"))))
                .isInstanceOf(TaxProviderException.class).hasMessageContaining("not implemented");
    }

    @Test
    void aChargingQuoteFallsBackVisiblyToTheRulesEngine() {
        List<TaxLineInput> lines = List.of(line("1", "PF050000"), line("2", null));
        when(rules.quote(any(), any(), any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE, List.of(),
                "rules"));

        TaxQuote q = calculator(false).quote(SHIP_TO, "USD", lines);

        assertThat(q.provider()).isEqualTo("RULES-FALLBACK");
        assertThat(q.fallbackLines()).containsExactly("1", "2");
        verify(telemetry).providerFallback("AVALARA", "not-configured", 2);
        calculator(true).quote(SHIP_TO, "USD", lines);
        verify(telemetry).providerFallback("AVALARA", "not-implemented", 2);
    }

    @Test
    void theRequestMapsLinesToAvaTaxTransactionLinesWithTheMappedCode() {
        Map<String, Object> body = calculator(true).request(SHIP_TO, "USD", List.of(line("1", "PF050000"), line("2", null)),
                "order-1");

        assertThat(body).containsEntry("type", "SalesOrder").containsEntry("companyCode", "AFROTRANSACT")
                .containsEntry("currencyCode", "USD").containsEntry("commit", false).containsEntry("customerCode", "order-1")
                .containsEntry("date", "2026-11-09");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        assertThat(lines).as("the line without an Avalara code is left out").singleElement().satisfies(l -> {
            assertThat(l).containsEntry("taxCode", "PF050000").containsEntry("amount", "18.00")
                    .containsEntry("quantity", 2).containsEntry("ref1", "1").containsEntry("itemCode", "AT1");
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> ship = (Map<String, Object>) ((Map<String, Object>) body.get("addresses")).get("shipTo");
        assertThat(ship).containsEntry("region", "TX").containsEntry("postalCode", "77002").containsEntry("country", "US");
    }
}
