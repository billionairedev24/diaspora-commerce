package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.application.TaxShadowService.Compare;
import com.afrotransact.taxreturns.application.TaxShadowService.Line;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.shadow.ShadowKind;
import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SHADOW: both answers compared and stored; a provider failure is a recorded row, never an exception. */
class TaxShadowServiceImplTest {

    private static final TaxAddress SHIP_TO = new TaxAddress("US", "TX", "77002", "Houston");

    private final InHouseTaxCalculator inHouse = mock(InHouseTaxCalculator.class);
    private final ProviderTaxCalculator stripe = mock(ProviderTaxCalculator.class);
    private final ShadowQuoteRepository repository = mock(ShadowQuoteRepository.class);
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final TaxShadowServiceImpl service = new TaxShadowServiceImpl(inHouse, List.of(stripe), repository, telemetry,
            new TaxReturnsProperties(new TaxReturnsProperties.Tax(null, null, null, null,
                    new TaxReturnsProperties.Shadow(Duration.ofMillis(300), 2, false)), null, null, null, null),
            TestActors.staff("tax:read"), TestRules.CLOCK);

    TaxShadowServiceImplTest() {
        when(stripe.provider()).thenReturn(TaxProviderId.STRIPE);
        when(stripe.configured()).thenReturn(true);
    }

    private static Line decided(String id, boolean exempt, Map<String, String> codes) {
        TaxLineInput in = new TaxLineInput(id, "AT" + id, "org", TaxCategory.GENERAL, 1, 10_000, 0, 0, "AT.X." + id, null,
                codes, exempt);
        return new Line(in, new LineDecision(ResolveStatus.RESOLVED, exempt ? Outcome.EXEMPT : Outcome.TAXABLE,
                "AT.X." + id, codes, "r1", null, "nuts-seeds", "x"));
    }

    private static Line blocked(String id) {
        TaxLineInput in = new TaxLineInput(id, "AT" + id, "org", TaxCategory.GENERAL, 1, 10_000, 0, 0);
        return new Line(in, LineDecision.blocked(ResolveStatus.MISSING_FACTS, "r1", null, "x"));
    }

    private static TaxLineResult tax(String id, long tax, String rate) {
        return new TaxLineResult(id, "org", TaxCategory.GENERAL, 10_000, new BigDecimal(rate), tax, "US-TX", null,
                CollectedBy.MARKETPLACE);
    }

    @SuppressWarnings("unchecked")
    private List<ShadowRow> saved() {
        ArgumentCaptor<List<ShadowRow>> c = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(c.capture());
        return c.getValue();
    }

    @Test
    void bothSidesAreComparedPerLineAndStoredWithTheirCodes() {
        Map<String, String> codes = Map.of("STRIPE", "txcd_1");
        when(inHouse.quote(any(), anyString(), any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE,
                List.of(tax("2", 825, "0.0825"), tax("3", 825, "0.0825")), "rules"));
        when(stripe.quoteStrict(any(), anyString(), any())).thenReturn(new TaxQuote("USD", "US-TX",
                CollectedBy.MARKETPLACE, List.of(tax("1", 0, "0"), tax("2", 825, "0.0825"), tax("3", 625, "0.0625")),
                "STRIPE_TAX"));

        service.run(new Compare(TaxProviderId.STRIPE, SHIP_TO, "USD", "order-1", List.of(decided("1", true, codes),
                decided("2", false, codes), decided("3", false, codes), blocked("4"))));

        assertThat(saved()).extracting(ShadowRow::lineId, ShadowRow::kind).containsExactly(
                tuple("4", ShadowKind.NO_DECISION),
                tuple("1", ShadowKind.MATCH),
                tuple("2", ShadowKind.MATCH),
                tuple("3", ShadowKind.RATE));
        verify(telemetry).shadow(TaxProviderId.STRIPE, ShadowKind.RATE);
    }

    @Test
    void aLineWithoutAMappedProviderCodeIsRecordedNotSentAndNeverDropped() {
        service.run(new Compare(TaxProviderId.STRIPE, SHIP_TO, "USD", "order-1", List.of(decided("1", false, Map.of()))));

        verify(stripe, never()).quoteStrict(any(), anyString(), any());
        assertThat(saved()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(ShadowKind.PROVIDER_ERROR);
            assertThat(r.providerError()).contains("No STRIPE code is mapped");
        });
    }

    @Test
    void aProviderFailureIsRecordedPerLineAndNeverThrown() {
        when(inHouse.quote(any(), anyString(), any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE,
                List.of(tax("1", 825, "0.0825")), "rules"));
        when(stripe.quoteStrict(any(), anyString(), any())).thenThrow(new TaxProviderException("Stripe Tax failed: 503"));

        service.run(new Compare(TaxProviderId.STRIPE, SHIP_TO, "USD", "order-1",
                List.of(decided("1", false, Map.of("STRIPE", "txcd_1")))));

        assertThat(saved()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(ShadowKind.PROVIDER_ERROR);
            assertThat(r.providerError()).contains("503");
            assertThat(r.inHouseTaxMinor()).isEqualTo(825);
        });
        verify(telemetry).shadowFailure(TaxProviderId.STRIPE, "error");
    }

    @Test
    void aSlowProviderTimesOutInsteadOfBlockingAnything() {
        when(inHouse.quote(any(), anyString(), any())).thenReturn(new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE,
                List.of(tax("1", 825, "0.0825")), "rules"));
        when(stripe.quoteStrict(any(), anyString(), any())).thenAnswer(i -> {
            Thread.sleep(2_000);
            return null;
        });

        long started = System.nanoTime();
        service.run(new Compare(TaxProviderId.STRIPE, SHIP_TO, "USD", "order-1",
                List.of(decided("1", false, Map.of("STRIPE", "txcd_1")))));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        assertThat(saved()).singleElement().satisfies(r -> assertThat(r.providerError()).contains("Timed out"));
        verify(telemetry).shadowFailure(TaxProviderId.STRIPE, "timeout");
    }

    @Test
    void anUnconfiguredShadowProviderIsRecordedAsSuch() {
        when(stripe.configured()).thenReturn(false);

        service.run(new Compare(TaxProviderId.STRIPE, SHIP_TO, "USD", "order-1",
                List.of(decided("1", true, Map.of("STRIPE", "txcd_1")))));

        assertThat(saved()).singleElement().satisfies(r -> assertThat(r.providerError()).contains("not configured"));
    }

    @Test
    void theReportGroupsByCodeCategoryOutcomeStateAndKind() {
        when(repository.totals(any(), any(), any())).thenReturn(new ShadowQuoteRepository.Totals(10, 2, 300, -100));

        TaxShadowService.Report r = service.report(LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-30"), TaxProviderId.STRIPE, true, 0, 50);

        assertThat(r.totals().differing()).isEqualTo(2);
        verify(repository).groups(ShadowQuoteRepository.Dimension.INTERNAL_TAX_CODE, Instant.parse(
                "2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), TaxProviderId.STRIPE);
        verify(repository).groups(ShadowQuoteRepository.Dimension.STATE, Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z"), TaxProviderId.STRIPE);
    }
}
