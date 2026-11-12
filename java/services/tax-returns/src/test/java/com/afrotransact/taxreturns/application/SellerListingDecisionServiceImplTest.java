package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.application.SellerListingDecisionService.State;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.ApprovalPolicy;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;

/**
 * S384: a seller who is not the page owner has no tax decision of its own until tax-returns decides for it from the page's
 * facts; the owner's decision is not the seller's, and nothing is ever stored for a record that is not approved.
 */
class SellerListingDecisionServiceImplTest {

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
    private final SellerListingDecisionService service = new SellerListingDecisionServiceImpl(records, lines,
            rules.engine, TestActors.staff("tax:classify", "tax:read"));

    private void report(String atin) {
        classifications.onCatalogFactsChanged(new CatalogFactsChanged(PRODUCT, "org_a", "nuts-seeds", "nuts-seeds",
                Map.of(), List.of(new Variant(atin, null, EXEMPT_NUTS, false)), "seller_1", TestRules.CLOCK.instant()));
    }

    @Test
    void aVariantWithNoRecordHasNoDecisionAndRequestingCreatesNothing() {
        State found = service.find("AT00000200", "org_b");
        State asked = service.request("AT00000200", "org_b", Map.of());

        assertThat(found.status()).isEqualTo("MISSING");
        assertThat(found.resolved()).isFalse();
        assertThat(asked.resolved()).isFalse();
        assertThat(records.decisions).isEmpty();
    }

    @Test
    void aRecordWaitingForReviewIsNotResolvedAndNothingIsStoredForTheSeller() {
        report("AT00000201");

        State asked = service.request("AT00000201", "org_b", Map.of());

        assertThat(asked.resolved()).isFalse();
        assertThat(asked.status()).isEqualTo("NEEDS_APPROVAL");
        assertThat(records.decisions.values()).noneMatch(d -> d.sellerOrgIds().contains("org_b"));
        assertThat(service.find("AT00000201", "org_a").resolved()).as("not even the owner's, while unapproved").isFalse();
    }

    @Test
    void theOwnersDecisionIsNotAnotherSellersUntilTaxReturnsDecidesForThem() {
        report("AT00000202");
        classifications.approve("AT00000202", new ApproveCommand("Checked", null, null, null));

        State owner = service.find("AT00000202", "org_a");
        State other = service.find("AT00000202", "org_b");

        assertThat(owner.resolved()).as("the owner's decision was stored when the record was approved").isTrue();
        assertThat(other.resolved()).as("another seller has none of its own").isFalse();
        assertThat(other.status()).isEqualTo("MISSING");

        State asked = service.request("AT00000202", "org_b", Map.of());

        assertThat(asked.resolved()).isTrue();
        assertThat(asked.status()).isEqualTo("RESOLVED");
        assertThat(asked.outcome()).isNotNull();
        assertThat(asked.ruleVersion()).isNotBlank();
        assertThat(service.find("AT00000202", "org_b").resolved()).as("stored, so a read finds it").isTrue();
        assertThat(service.find("AT00000202", "org_c").resolved()).as("a third seller still has none").isFalse();
    }

    @Test
    void askingTwiceIsIdempotent() {
        report("AT00000203");
        classifications.approve("AT00000203", new ApproveCommand("Checked", null, null, null));

        State first = service.request("AT00000203", "org_b", Map.of());
        int stored = records.decisions.size();
        State second = service.request("AT00000203", "org_b", Map.of());

        assertThat(first.resolved()).isTrue();
        assertThat(second.resolved()).isTrue();
        assertThat(records.decisions).hasSize(stored);
    }
}
