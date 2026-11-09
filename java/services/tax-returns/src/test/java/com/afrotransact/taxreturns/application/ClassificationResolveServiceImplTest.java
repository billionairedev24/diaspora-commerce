package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.application.ClassificationResolveService.ResolveCommand;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;

/** The resolve use case over the real dataset: every status, the provider codes from the map, and the cache. */
class ClassificationResolveServiceImplTest {

    private final TestRules rules = new TestRules();
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final ClassificationResolveService service = new ClassificationResolveServiceImpl(rules.engine,
            TestRules.props(null), telemetry, TestActors.staff("tax:classify"));

    private static ResolveCommand cmd(String category, Map<String, Object> facts) {
        return new ResolveCommand(category, null, facts, Map.of(), Map.of(), null, null);
    }

    private static final Map<String, Object> PLAIN_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 8, "servings_per_package", 4, "individual_serving", false);

    @Test
    void aCategoryWithoutFactsIsResolvedWithItsOutcomeAndTheMappedProviderCodes() {
        Resolution r = service.resolve(cmd("rice-grains", Map.of()));

        assertThat(r.status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(r.outcome()).isEqualTo(Outcome.EXEMPT);
        assertThat(r.internalTaxCode()).isEqualTo("AT.RICE_GRAINS.STANDARD");
        assertThat(r.providerCodes()).containsEntry("STRIPE", "txcd_40010002").doesNotContainKey("AVALARA");
        assertThat(r.ruleVersion()).isEqualTo(TestRules.RULES_V1);
    }

    @Test
    void missingFactsGiveNoOutcomeAndPlainMessages() {
        Resolution r = service.resolve(cmd("nuts-seeds", Map.of("is_candy", false)));

        assertThat(r.status()).isEqualTo(ResolveStatus.MISSING_FACTS);
        assertThat(r.outcome()).isNull();
        assertThat(r.internalTaxCode()).isNull();
        assertThat(r.missingFacts()).extracting(m -> m.factId()).contains("candy_coated");
        assertThat(r.missingFacts()).allSatisfy(m -> assertThat(m.message()).contains("is needed."));
    }

    @Test
    void theSameFactsGiveTheSameAnswerAndTheCacheServesTheRepeat() {
        Resolution first = service.resolve(cmd("nuts-seeds", PLAIN_NUTS));
        Resolution again = service.resolve(cmd("nuts-seeds", PLAIN_NUTS));

        assertThat(first.outcome()).isEqualTo(Outcome.EXEMPT);
        assertThat(again).isEqualTo(first);
        verify(rules.codeRepo, times(1)).forCodes(anyCollection());
    }

    @Test
    void aNewRuleVersionBypassesTheCachedAnswer() {
        service.resolve(cmd("nuts-seeds", PLAIN_NUTS));
        rules.ruleVersion = "tx-test.1+1";
        rules.catalog.refresh();

        Resolution r = service.resolve(cmd("nuts-seeds", PLAIN_NUTS));

        assertThat(r.ruleVersion()).isEqualTo("tx-test.1+1");
        verify(rules.codeRepo, times(2)).forCodes(anyCollection());
    }

    @Test
    void everyGateBlocksTheDecisionWithoutDroppingTheOutcome() {
        assertThat(service.resolve(cmd("flavored-water-soda", Map.of())).status()).isEqualTo(ResolveStatus.CONFLICT_OPEN);
        assertThat(service.resolve(cmd("flavored-water-soda", Map.of())).openDecision()).isEqualTo(1);
        assertThat(service.resolve(cmd("toys-games", Map.of())).status()).isEqualTo(ResolveStatus.REFINE_CODE);
        Resolution digital = service.resolve(cmd("audiobooks", Map.of("delivery_method", "DOWNLOAD",
                "exemption_confirmed", false)));
        assertThat(digital.status()).as("%s", digital.reason()).isIn(ResolveStatus.NEEDS_APPROVAL,
                ResolveStatus.MISSING_FACTS);
        Resolution bundle = service.resolve(cmd("gift-baskets-bundles", Map.of("bundle_line_kind",
                "UNITEMIZED_MIXED_BUNDLE")));
        assertThat(bundle.status()).isEqualTo(ResolveStatus.BLOCKED);
        assertThat(bundle.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(bundle.chargeable()).isFalse();
    }

    @Test
    void liftingTheGateMakesTheSameFactsResolved() {
        assertThat(service.resolve(cmd("toys-games", Map.of())).chargeable()).isFalse();
        rules.status("toys-games", ProfileStatus.ACTIVE);
        assertThat(service.resolve(cmd("toys-games", Map.of())).status()).isEqualTo(ResolveStatus.RESOLVED);
    }

    @Test
    void theExemptionWindowIsFalseUntilTheHolidayCalendarExists() {
        Resolution r = service.resolve(new ResolveCommand("car-seats", null, Map.of("holiday_eligible", true), Map.of(),
                Map.of(), null, null));

        assertThat(r.status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(r.outcome()).as("ordinary taxable sale, the safe direction").isEqualTo(Outcome.TAXABLE);
        assertThat(r.internalTaxCode()).isEqualTo("AT.CAR_SEATS.ORDINARY_SALE");
    }

    @Test
    void aStateTheRulesDoNotCoverIsBlockedNotGuessed() {
        Resolution r = service.resolve(new ResolveCommand("rice-grains", null, Map.of(), Map.of(), Map.of(), "CA", null));

        assertThat(r.status()).isEqualTo(ResolveStatus.BLOCKED);
        assertThat(r.outcome()).isNull();
        assertThat(r.reason()).contains("TX").contains("CA");
    }

    @Test
    void anUnknownCategoryIsNotFound() {
        assertThatThrownBy(() -> service.resolve(cmd("no-such-category", Map.of()))).isInstanceOf(NotFoundException.class);
    }
}
