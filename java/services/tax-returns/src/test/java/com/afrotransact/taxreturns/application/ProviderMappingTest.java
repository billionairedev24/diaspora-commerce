package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import org.junit.jupiter.api.Test;

/**
 * The cutover risk, pinned: one provider code appears on several outcomes (with different results), so the mapping is
 * per OUTCOME code and a change to one outcome never touches another.
 */
class ProviderMappingTest {

    private final TestRules rules = new TestRules();

    @Test
    void theDatasetReallyUsesOneStripeCodeForDifferentOutcomes() {
        Map<String, Set<String>> outcomesByCode = rules.codes.stream().filter(c -> c.provider() == TaxProviderId.STRIPE)
                .collect(Collectors.groupingBy(ProviderCode::providerCode, Collectors.mapping(
                        c -> c.outcome().name(), Collectors.toSet())));
        assertThat(outcomesByCode.get("txcd_40040000")).as("exempt dairy, frozen meals and exempt gift food").hasSize(1)
                .contains("EXEMPT");
        assertThat(outcomesByCode.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey))
                .as("codes that carry both EXEMPT and TAXABLE outcomes").isNotEmpty();
        long internalCodesOnOneStripeCode = rules.codes.stream().filter(c -> "txcd_41053001".equals(c.providerCode()))
                .map(ProviderCode::internalTaxCode).distinct().count();
        assertThat(internalCodesOnOneStripeCode).isGreaterThanOrEqualTo(4);
    }

    @Test
    void eachOutcomeCodeHasItsOwnEntryPerProvider() {
        assertThat(rules.codes).filteredOn(c -> c.provider() == TaxProviderId.STRIPE)
                .extracting(ProviderCode::internalTaxCode).doesNotHaveDuplicates();
    }

    @Test
    void remappingOneOutcomeLeavesTheOthersOnTheSameProviderCodeAlone() {
        List<String> sharing = rules.codes.stream().filter(c -> "txcd_41053001".equals(c.providerCode()))
                .map(ProviderCode::internalTaxCode).toList();
        String changed = sharing.getFirst();
        rules.codes.replaceAll(c -> c.internalTaxCode().equals(changed) && c.provider() == TaxProviderId.STRIPE
                ? new ProviderCode(c.internalTaxCode(), c.categoryCode(), c.outcome(), c.provider(), "txcd_99999999",
                c.active(), c.verified(), c.verifiedNote(), 2, "staff", c.changedAt()) : c);

        Map<String, Map<String, String>> usable = rules.engine.usableCodes(sharing, false);

        assertThat(usable.get(changed)).containsEntry("STRIPE", "txcd_99999999");
        sharing.stream().filter(c -> !c.equals(changed))
                .forEach(c -> assertThat(usable.get(c)).containsEntry("STRIPE", "txcd_41053001"));
    }

    @Test
    void unmappedInactiveAndUnverifiedEntriesAreNotSentWhenVerificationIsRequired() {
        String code = "AT.RICE_GRAINS.STANDARD";
        assertThat(rules.engine.usableCodes(List.of(code), false).get(code)).containsEntry("STRIPE", "txcd_40010002");
        assertThat(rules.engine.usableCodes(List.of(code), true)).as("seeded unverified").doesNotContainKey(code);

        rules.verify(code, true);
        assertThat(rules.engine.usableCodes(List.of(code), true).get(code)).containsEntry("STRIPE", "txcd_40010002")
                .doesNotContainKey("AVALARA");

        rules.codes.replaceAll(c -> c.internalTaxCode().equals(code) ? new ProviderCode(c.internalTaxCode(),
                c.categoryCode(), c.outcome(), c.provider(), c.providerCode(), false, true, null, 3, "staff",
                c.changedAt()) : c);
        assertThat(rules.engine.usableCodes(List.of(code), false)).as("an inactive mapping is not usable")
                .doesNotContainKey(code);
    }

    @Test
    void aRefineCodeCategoryIsLiftedPerVariantByItsVerifiedRefinedCode() {
        VariantClassification plain = record("toys-games", Map.of());
        VariantClassification refined = record("toys-games", Map.of("STRIPE", "txcd_20010001"));

        Resolution blocked = rules.engine.resolveRecord(plain, Map.of(), TestRules.NOW.atZone(
                ZoneOffset.UTC).toLocalDate());
        Resolution lifted = rules.engine.resolveRecord(refined, Map.of(), TestRules.NOW.atZone(
                ZoneOffset.UTC).toLocalDate());

        assertThat(blocked.status()).isEqualTo(ResolveStatus.REFINE_CODE);
        assertThat(lifted.status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(lifted.providerCodes()).containsEntry("STRIPE", "txcd_20010001");
    }

    private static VariantClassification record(String category, Map<String, String> refined) {
        return new VariantClassification("AT00000001", null, null, null, "org_a", category, category, Map.of(), Map.of(),
                Map.of(), refined, RecordStatus.APPROVED, null, "r", "s", TestRules.NOW, "r", TestRules.NOW, null, 0,
                TestRules.NOW, TestRules.NOW);
    }
}
