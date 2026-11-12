package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.application.ListingDecisionStatusService.Item;
import com.afrotransact.taxreturns.application.LineDecisionService.ListingStatus;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.ApprovalPolicy;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;

/**
 * The authority behind the catalog publish gate: a variant is RESOLVED only when tax-returns' stored record is APPROVED
 * and the rules still give a definite outcome, exactly what checkout's quote requires.
 */
class ListingDecisionStatusServiceImplTest {

    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final Map<String, Object> EXEMPT_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 8, "servings_per_package", 4, "individual_serving", false);

    private final TestRules rules = new TestRules();
    private final InMemoryClassifications records = new InMemoryClassifications();
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final TaxReturnsProperties props = TestRules.props(
            new TaxReturnsProperties.Classification(null, ApprovalPolicy.ALL, false, null));
    private final VariantClassificationService classifications = new VariantClassificationServiceImpl(records,
            rules.engine, new ListingDecisions(records),
            new ClassificationEvents(mock(DomainEventPublisher.class), telemetry), props, new InMemoryReviewSettings(),
            TestActors.staff("tax:classify-admin", "tax:read"), TestRules.CLOCK);
    private final ProductFacts noFacts = atins -> Map.of();
    private final LineDecisionService lines = new LineDecisionServiceImpl(records, classifications, noFacts,
            rules.engine, new ListingDecisions(records), props, telemetry, TestRules.CLOCK);
    private final ListingDecisionStatusService service = new ListingDecisionStatusServiceImpl(lines, rules.engine,
            TestActors.staff("tax:classify"));

    private void report(String atin) {
        classifications.onCatalogFactsChanged(new CatalogFactsChanged(PRODUCT, "org_a", "nuts-seeds", "nuts-seeds",
                Map.of(), List.of(new Variant(atin, null, EXEMPT_NUTS, false)), "seller_1", TestRules.CLOCK.instant()));
    }

    @Test
    void aVariantWithNoRecordIsNeverResolvedAndNothingIsCreated() {
        Map<String, ListingStatus> out = service.statusOf(List.of(new Item("AT00000100", "org_a")));

        assertThat(out.get("AT00000100").status()).isEqualTo(ResolveStatus.MISSING_FACTS);
        assertThat(out.get("AT00000100").resolved()).isFalse();
        assertThat(records.records).isEmpty();
    }

    @Test
    void aRecordWaitingForReviewIsNotResolvedEvenThoughTheRulesGiveAnOutcome() {
        report("AT00000101");

        ListingStatus s = service.statusOf(List.of(new Item("AT00000101", "org_a"))).get("AT00000101");

        assertThat(s.resolved()).isFalse();
        assertThat(s.status()).isEqualTo(ResolveStatus.NEEDS_APPROVAL);
        assertThat(s.recordStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void anApprovedRecordIsResolvedAndANonApprovedOneInTheSameBatchIsNot() {
        report("AT00000102");
        report("AT00000103");
        classifications.approve("AT00000102", new ApproveCommand("Checked", null, null, null));

        Map<String, ListingStatus> out = service.statusOf(List.of(new Item("AT00000102", "org_a"),
                new Item("AT00000103", "org_a"), new Item("AT00000102", "org_a")));

        assertThat(out).hasSize(2);
        assertThat(out.get("AT00000102").resolved()).isTrue();
        assertThat(out.get("AT00000103").resolved()).isFalse();
    }
}
