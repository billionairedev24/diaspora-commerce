package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.ApprovalPolicy;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.DefaultTaxRules;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The quote path over the stored decisions: decided lines are taxed or priced at zero, undecided lines come back BLOCKED
 * with a plain reason and no rate, and only taxable lines ever reach the calculator.
 */
class TaxServiceImplTest {

    private static final TaxAddress TEXAS = new TaxAddress("US", "TX", "77002", "Houston");
    private static final Map<String, Object> EXEMPT_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 8, "servings_per_package", 4, "individual_serving", false);
    private static final Map<String, Object> TAXABLE_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 1, "servings_per_package", 1, "individual_serving", true);

    private final TestRules rules = new TestRules();
    private final InMemoryClassifications records = new InMemoryClassifications();
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    private final TaxCalculator calculator = mock(TaxCalculator.class);
    private final TaxShadowService shadow = mock(TaxShadowService.class);
    private final TaxMethodSource methods = mock(TaxMethodSource.class);
    private final Map<String, ProductFacts.Facts> catalogFacts = new HashMap<>();
    private final List<List<TaxLineInput>> sent = new ArrayList<>();
    private final VariantClassificationService classifications;
    private TaxService service;

    TaxServiceImplTest() {
        TaxReturnsProperties props = props(false, false);
        classifications = new VariantClassificationServiceImpl(records, rules.engine, new ListingDecisions(records),
                new ClassificationEvents(publisher, telemetry), props, TestActors.staff("*"), TestRules.CLOCK);
        service = build(props);
        when(methods.shadowProvider()).thenReturn(Optional.empty());
        when(calculator.quote(any(), anyString(), any())).thenAnswer(i -> {
            List<TaxLineInput> in = i.getArgument(2);
            sent.add(in);
            return new TaxQuote("USD", "US-TX", CollectedBy.MARKETPLACE, in.stream().map(l -> new TaxLineResult(
                    l.lineId(), l.sellerOrgId(), l.category(), l.taxableItemsMinor(), new BigDecimal("0.0825"),
                    DefaultTaxRules.taxOf(l.taxableItemsMinor(), new BigDecimal("0.0825")), "US-TX", "US-TX-770",
                    CollectedBy.MARKETPLACE, 0L, l.taxCode(), null, "ZIP")).toList(), "rules");
        });
    }

    private static TaxReturnsProperties props(boolean requireVerified, boolean cartShadow) {
        return new TaxReturnsProperties(new TaxReturnsProperties.Tax(null, null, null,
                new TaxReturnsProperties.Classification(null, ApprovalPolicy.ALL, requireVerified, null),
                new TaxReturnsProperties.Shadow(null, null, cartShadow)), null, null, null, null);
    }

    private TaxService build(TaxReturnsProperties props) {
        ProductFacts facts = atins -> {
            Map<String, ProductFacts.Facts> out = new HashMap<>(catalogFacts);
            out.keySet().retainAll(atins);
            return out;
        };
        LineDecisionService decisions = new LineDecisionServiceImpl(records, classifications, facts, rules.engine,
                new ListingDecisions(records), props, telemetry, TestRules.CLOCK);
        return new TaxServiceImpl(calculator, mock(TaxRateRepository.class), decisions, shadow, methods, telemetry, props,
                TestActors.staff("tax:quote"), TestRules.CLOCK);
    }

    private void approved(String atin, String category, Map<String, Object> facts) {
        approved(atin, category, facts, Map.of());
    }

    private void approved(String atin, String category, Map<String, Object> facts, Map<String, Object> ownerBehaviour) {
        classifications.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", category, category,
                ownerBehaviour, List.of(new Variant(atin, null, facts, false)), "seller_1", TestRules.CLOCK.instant()));
        classifications.approve(atin, new ApproveCommand(null, null, null, null));
    }

    private static TaxQuoteCommand.Line line(String id, String atin, long unit) {
        return new TaxQuoteCommand.Line(id, atin, "org_a", null, null, 1, unit, 0, 0);
    }

    private TaxQuote quote(TaxQuoteCommand.Line... lines) {
        return service.quote(new TaxQuoteCommand("USD", TEXAS, List.of(lines)));
    }

    @Test
    void decidedLinesAreTaxedOrZeroAndUndecidedLinesAreBlockedWithNoRate() {
        approved("ATEXEMPT01", "nuts-seeds", EXEMPT_NUTS);
        approved("ATTAXABLE1", "nuts-seeds", TAXABLE_NUTS);
        classifications.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", "nuts-seeds",
                "nuts-seeds", Map.of(), List.of(new Variant("ATPENDING1", null, TAXABLE_NUTS, false)), "s",
                TestRules.CLOCK.instant()));
        classifications.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", "nuts-seeds",
                "nuts-seeds", Map.of(), List.of(new Variant("ATMISSING1", null, Map.of(), false)), "s",
                TestRules.CLOCK.instant()));

        TaxQuote q = quote(line("1", "ATEXEMPT01", 1_000), line("2", "ATTAXABLE1", 2_000), line("3", "ATPENDING1", 3_000),
                line("4", "ATMISSING1", 4_000), line("5", null, 5_000), line("6", "ATUNKNOWN1", 6_000));

        assertThat(q.lines()).extracting(l -> l.lineId()).containsExactly("1", "2", "3", "4", "5", "6");
        TaxLineResult exempt = q.lines().get(0);
        assertThat(exempt.decision().status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(exempt.decision().outcome().name()).isEqualTo("EXEMPT");
        assertThat(exempt.taxMinor()).isZero();
        assertThat(exempt.taxCode()).isEqualTo("AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS");
        TaxLineResult taxable = q.lines().get(1);
        assertThat(taxable.taxMinor()).isEqualTo(165);
        assertThat(taxable.decision().internalTaxCode()).isEqualTo("AT.NUTS_SEEDS.PLAIN_SINGLE_OR_UNDER_2_5OZ");
        assertThat(taxable.decision().ruleVersion()).isEqualTo(TestRules.RULES_V1);
        assertThat(taxable.addressPrecision()).isEqualTo("ZIP");
        assertThat(q.lines().subList(2, 6)).allSatisfy(l -> {
            assertThat(l.decision().isBlocked()).isTrue();
            assertThat(l.decision().blockedReason()).startsWith("This item can't be bought right now");
            assertThat(l.rate()).as("no rate on a blocked line").isNull();
            assertThat(l.taxMinor()).isZero();
            assertThat(l.taxableMinor()).isZero();
        });
        assertThat(q.lines().get(2).decision().status()).isEqualTo(ResolveStatus.NEEDS_APPROVAL);
        assertThat(q.lines().get(3).decision().status()).isEqualTo(ResolveStatus.MISSING_FACTS);
        assertThat(q.lines().get(4).decision().status()).isEqualTo(ResolveStatus.MISSING_FACTS);
        assertThat(q.lines().get(5).decision().status()).isEqualTo(ResolveStatus.MISSING_FACTS);
        assertThat(sent).as("only the taxable line reaches the calculator").singleElement()
                .satisfies(l -> assertThat(l).extracting(TaxLineInput::lineId).containsExactly("2"));
        assertThat(q.totalTaxMinor()).isEqualTo(165);
    }

    @Test
    void anOrderOfOnlyExemptOrBlockedLinesMakesNoProviderCall() {
        approved("ATEXEMPT01", "nuts-seeds", EXEMPT_NUTS);

        TaxQuote q = quote(line("1", "ATEXEMPT01", 1_000), line("2", "ATUNKNOWN1", 500));

        verify(calculator, never()).quote(any(), anyString(), any());
        assertThat(q.provider()).isEqualTo("EXEMPT");
        assertThat(q.collectedBy()).isEqualTo(CollectedBy.NOT_COLLECTED);
        assertThat(q.totalTaxMinor()).isZero();
    }

    @Test
    void theSellersBehaviourIsPartOfTheDecision() {
        approved("ATBAKERY01", "bakery-products", Map.of(), Map.of("sold_heated", false, "utensils_provided", false));
        TaxQuoteCommand.Line unheated = new TaxQuoteCommand.Line("1", "ATBAKERY01", "org_a", null, null, 1, 1_000, 0, 0,
                Map.of("sold_heated", false, "utensils_provided", false));
        TaxQuoteCommand.Line heated = new TaxQuoteCommand.Line("2", "ATBAKERY01", "org_b", null, null, 1, 1_000, 0, 0,
                Map.of("sold_heated", true, "heated_by_seller", true, "utensils_provided", false));
        TaxQuoteCommand.Line unsaid = new TaxQuoteCommand.Line("3", "ATBAKERY01", "org_c", null, null, 1, 1_000, 0, 0,
                Map.of());

        TaxQuote q = quote(unheated, heated, unsaid);

        assertThat(q.lines().get(0).decision().outcome().name()).isEqualTo("EXEMPT");
        assertThat(q.lines().get(1).decision().outcome().name()).isEqualTo("TAXABLE");
        assertThat(q.lines().get(2).decision().status()).as("the behaviour was not said, so nothing is guessed")
                .isEqualTo(ResolveStatus.MISSING_FACTS);
        assertThat(records.decisions("ATBAKERY01")).as("one stored decision per distinct decided behaviour (the unheated one is the owner's)")
                .hasSize(2).extracting(d -> d.outcome().name()).containsExactlyInAnyOrder("EXEMPT", "TAXABLE");
        assertThat(records.decisions("ATBAKERY01").stream().filter(d -> d.outcome().name().equals("EXEMPT")).findFirst()
                .orElseThrow().sellerOrgIds()).containsExactly("org_a");
    }

    @Test
    void aProfileGateClosedAfterApprovalBlocksTheLineAtOnce() {
        approved("ATTAXABLE1", "nuts-seeds", TAXABLE_NUTS);
        assertThat(quote(line("1", "ATTAXABLE1", 1_000)).lines().getFirst().decision().status())
                .isEqualTo(ResolveStatus.RESOLVED);

        rules.status("nuts-seeds", ProfileStatus.NEEDS_APPROVAL);

        assertThat(quote(line("1", "ATTAXABLE1", 1_000)).lines().getFirst().decision().status())
                .isEqualTo(ResolveStatus.NEEDS_APPROVAL);
    }

    @Test
    void aVariantWithoutARecordIsAskedOfCatalogOnceQueuedForReviewAndBlocked() {
        catalogFacts.put("ATFRESH001", new ProductFacts.Facts("ATFRESH001", "nuts-seeds", false, Map.of(), "nuts-seeds",
                TAXABLE_NUTS, Map.of()));

        TaxQuote q = quote(line("1", "ATFRESH001", 1_000));

        assertThat(q.lines().getFirst().decision().status()).isEqualTo(ResolveStatus.NEEDS_APPROVAL);
        assertThat(records.records.get("ATFRESH001").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
    }

    @Test
    void aCallerChosenCategoryStillWorksAsBeforeAndIsCountedAsExplicit() {
        TaxQuote q = quote(new TaxQuoteCommand.Line("1", "ATANY", "org_a", "GENERAL", null, 1, 1_000, 0, 0));

        assertThat(q.lines().getFirst().decision()).isNull();
        assertThat(q.lines().getFirst().taxMinor()).isEqualTo(83);
        verify(telemetry).quoteLine("EXPLICIT", "CATEGORY");
    }

    @Test
    void aShipToOutsideTheRulesStatesBlocksEveryDecidedLine() {
        approved("ATTAXABLE1", "nuts-seeds", TAXABLE_NUTS);

        TaxQuote q = service.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "CA", "90001", null),
                List.of(line("1", "ATTAXABLE1", 1_000))));

        assertThat(q.lines().getFirst().decision().status()).isEqualTo(ResolveStatus.BLOCKED);
        assertThat(q.lines().getFirst().rate()).isNull();
    }

    @Test
    void theProviderCodesOfTheOutcomeTravelWithTheLineAndUnverifiedOnesCanBeWithheld() {
        approved("ATTAXABLE1", "nuts-seeds", TAXABLE_NUTS);
        quote(line("1", "ATTAXABLE1", 1_000));
        assertThat(sent.getFirst().getFirst().providerCodes()).containsEntry("STRIPE", "txcd_40040016");

        sent.clear();
        service = build(props(true, false));
        quote(line("1", "ATTAXABLE1", 1_000));
        assertThat(sent.getFirst().getFirst().providerCodes()).as("unverified: not sent").isEmpty();

        rules.verify("AT.NUTS_SEEDS.PLAIN_SINGLE_OR_UNDER_2_5OZ", true);
        sent.clear();
        quote(line("1", "ATTAXABLE1", 1_000));
        assertThat(sent.getFirst().getFirst().providerCodes()).containsEntry("STRIPE", "txcd_40040016");
    }

    @Test
    void shadowComparesRealOrdersOnlyAndIncludesExemptAndBlockedLines() {
        approved("ATEXEMPT01", "nuts-seeds", EXEMPT_NUTS);
        approved("ATTAXABLE1", "nuts-seeds", TAXABLE_NUTS);
        when(methods.shadowProvider()).thenReturn(Optional.of(TaxProviderId.STRIPE));

        quote(line("1", "ATEXEMPT01", 1_000), line("2", "ATTAXABLE1", 1_000));
        verify(shadow, never()).compare(any());

        service.quote(new TaxQuoteCommand("USD", TEXAS, List.of(line("1", "ATEXEMPT01", 1_000),
                line("2", "ATTAXABLE1", 1_000), line("3", "ATUNKNOWN1", 100)), null, "order-1"));

        ArgumentCaptor<TaxShadowService.Compare> c = ArgumentCaptor.forClass(TaxShadowService.Compare.class);
        verify(shadow).compare(c.capture());
        assertThat(c.getValue().provider()).isEqualTo(TaxProviderId.STRIPE);
        assertThat(c.getValue().reference()).isEqualTo("order-1");
        assertThat(c.getValue().lines()).hasSize(3);
        assertThat(c.getValue().lines().get(0).input().exempt()).isTrue();
        assertThat(c.getValue().lines().get(2).decision().isBlocked()).isTrue();
    }
}
