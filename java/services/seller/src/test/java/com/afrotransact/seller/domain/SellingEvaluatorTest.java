package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.SellingEvaluator.Filed;
import org.junit.jupiter.api.Test;

class SellingEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final List<SellingDocumentRequirement> DOCS = List.of(
            new SellingDocumentRequirement(SellingDocumentKind.PERMIT_LICENCE, false));

    private static SellingRule rule(SellingScope scope, SellingLevel level, String label) {
        return new SellingRule(UUID.randomUUID(), scope, label, level, DOCS, level == SellingLevel.NOT_AVAILABLE
                ? "Blocked for now" : "", "", null, null, Set.of(), false, 30, true, "SEED", NOW, null, 0);
    }

    private static SellingApproval approved(SellingScope scope, Instant expires) {
        SellingApplication app = new SellingApplication("a1", "SA-1", "org", "u", "U", scope, "x", UUID.randomUUID(),
                "NEW", "SELLER", ApplicationStatus.APPROVED, List.of(new SellingDocument("d", SellingDocumentKind.PERMIT_LICENCE,
                "asset", "f.pdf", null, expires.atZone(ZoneOffset.UTC).toLocalDate(), false, null)), null, null, null,
                null, null, List.of(), List.of(), null, null, false, null, 1, NOW, NOW, NOW, 0);
        return SellingApproval.applied("org", rule(scope, SellingLevel.APPLY, "x"), "a1", NOW).approved(app, NOW);
    }

    private static SellingFacts facts(String category, String brand) {
        return new SellingFacts(category, brand, null, null, null, false);
    }

    @Test
    void noRulesOrOnlySellRulesMeanSell() {
        assertThat(SellingEvaluator.evaluate(facts("snacks", null), List.of(), Map.of(), Map.of(), NOW).level())
                .isEqualTo(SellingLevel.SELL);
        SellingRule open = rule(SellingScope.category("snacks"), SellingLevel.SELL, "Snacks");
        assertThat(SellingEvaluator.evaluate(facts("snacks", null), List.of(open), Map.of(), Map.of(), NOW).level())
                .isEqualTo(SellingLevel.SELL);
    }

    @Test
    void anApplyRuleWithoutAnApprovalSaysApplyAndWhatIsMissing() {
        SellingRule r = rule(SellingScope.category("supplements"), SellingLevel.APPLY, "Dietary supplements");
        SellingStatus s = SellingEvaluator.evaluate(facts("supplements", null), List.of(r), Map.of(), Map.of(), NOW);
        assertThat(s.level()).isEqualTo(SellingLevel.APPLY);
        assertThat(s.missing()).hasSize(1);
        assertThat(s.missing().getFirst().state()).isEqualTo(SellingApprovalStatus.NOT_APPLIED);
        assertThat(s.missing().getFirst().canApply()).isTrue();
        assertThat(s.missing().getFirst().documents()).isEqualTo(DOCS);
        assertThat(s.reason()).contains("Dietary supplements");
    }

    @Test
    void aValidApprovalSellsAnExpiredOneAsksToRenewAndAPausedOneIsNotAvailable() {
        SellingScope scope = SellingScope.category("supplements");
        SellingRule r = rule(scope, SellingLevel.APPLY, "Dietary supplements");
        SellingApproval ok = approved(scope, NOW.plusSeconds(86400L * 40));
        assertThat(SellingEvaluator.evaluate(facts("supplements", null), List.of(r), Map.of(scope, ok), Map.of(), NOW)
                .level()).isEqualTo(SellingLevel.SELL);
        SellingApproval expired = approved(scope, NOW.minusSeconds(86400L * 2));
        SellingStatus s = SellingEvaluator.evaluate(facts("supplements", null), List.of(r), Map.of(scope, expired),
                Map.of(), NOW);
        assertThat(s.level()).isEqualTo(SellingLevel.APPLY);
        assertThat(s.missing().getFirst().state()).isEqualTo(SellingApprovalStatus.EXPIRED);
        assertThat(s.missing().getFirst().message()).contains("expired on 9 November 2026");
        SellingApproval paused = ok.withPause(true, "late shipments", NOW);
        SellingStatus p = SellingEvaluator.evaluate(facts("supplements", null), List.of(r), Map.of(scope, paused),
                Map.of(), NOW);
        assertThat(p.level()).isEqualTo(SellingLevel.NOT_AVAILABLE);
        assertThat(p.reason()).contains("paused").contains("late shipments");
    }

    @Test
    void disqualificationShowsTheReasonAndTheReapplyDateThenAllowsApplyingAgain() {
        SellingScope scope = SellingScope.category("supplements");
        SellingRule r = rule(scope, SellingLevel.APPLY, "Dietary supplements");
        SellingApproval a = SellingApproval.applied("org", r, "a1", NOW).with(SellingApprovalStatus.NOT_AVAILABLE,
                "Your account does not qualify", LocalDate.of(2027, 1, 15), "a1", NOW);
        SellingStatus s = SellingEvaluator.evaluate(facts("supplements", null), List.of(r), Map.of(scope, a), Map.of(), NOW);
        assertThat(s.level()).isEqualTo(SellingLevel.NOT_AVAILABLE);
        assertThat(s.reason()).contains("does not qualify").contains("cannot be appealed").contains("15 January 2027");
        assertThat(s.missing().getFirst().canApply()).isFalse();
        Instant later = LocalDate.of(2027, 1, 16).atStartOfDay().toInstant(ZoneOffset.UTC);
        SellingStatus again = SellingEvaluator.evaluate(facts("supplements", null), List.of(r), Map.of(scope, a), Map.of(),
                later);
        assertThat(again.level()).isEqualTo(SellingLevel.APPLY);
        assertThat(again.missing().getFirst().canApply()).isTrue();
    }

    @Test
    void notAvailableDominatesAndAppliedWaitsWithoutAButton() {
        SellingRule blocked = rule(SellingScope.category("bundles"), SellingLevel.NOT_AVAILABLE, "Bundles");
        SellingRule apply = rule(SellingScope.brand("Gucci"), SellingLevel.APPLY, "Gucci");
        SellingFacts f = new SellingFacts("bundles", "Gucci", null, null, null, false);
        assertThat(SellingEvaluator.evaluate(f, List.of(apply, blocked), Map.of(), Map.of(), NOW).level())
                .isEqualTo(SellingLevel.NOT_AVAILABLE);
        SellingApproval applied = SellingApproval.applied("org", apply, "a1", NOW);
        SellingStatus s = SellingEvaluator.evaluate(new SellingFacts("snacks", "Gucci", null, null, null, false),
                List.of(apply), Map.of(apply.scope(), applied), Map.of(apply.scope(), new Filed("a1",
                ApplicationStatus.PENDING_REVIEW)), NOW);
        assertThat(s.missing().getFirst().state()).isEqualTo(SellingApprovalStatus.APPLIED);
        assertThat(s.missing().getFirst().canApply()).isFalse();
        assertThat(s.missing().getFirst().applicationId()).isEqualTo("a1");
    }

    @Test
    void conditionalJewelryAppliesToGoldSilverStonesAndWatchesOnly() {
        SellingRule jewelry = new SellingRule(UUID.randomUUID(), SellingScope.category("jewelry"), "Jewelry",
                SellingLevel.APPLY, DOCS, "", "", "conditional_approval", null, Set.of("gold", "silver", "gemstone"), true,
                30, true, "SEED", NOW, null, 0);
        assertThat(level(jewelry, "jewelry", "beads", false)).isEqualTo(SellingLevel.SELL);
        assertThat(level(jewelry, "jewelry", "14K Gold plated", false)).isEqualTo(SellingLevel.APPLY);
        assertThat(level(jewelry, "jewelry", "Sterling silver", false)).isEqualTo(SellingLevel.APPLY);
        assertThat(level(jewelry, "jewelry", "steel", true)).isEqualTo(SellingLevel.APPLY);
        SellingStatus unknown = SellingEvaluator.evaluate(facts("jewelry", null), List.of(jewelry), Map.of(), Map.of(), NOW);
        assertThat(unknown.level()).isEqualTo(SellingLevel.SELL);
        assertThat(unknown.pendingFacts()).containsExactly("material");
    }

    private static SellingLevel level(SellingRule r, String category, String material, boolean watch) {
        return SellingEvaluator.evaluate(new SellingFacts(category, null, null, material, null, watch), List.of(r),
                Map.of(), Map.of(), NOW).level();
    }

    @Test
    void aCapabilityIsOneApprovalForEveryCategoryThatNeedsIt() {
        SellingRule food = rule(SellingScope.capability("FOOD_SERVICE"), SellingLevel.APPLY, "Food service");
        SellingRule bakery = new SellingRule(UUID.randomUUID(), SellingScope.category("bakery"), "Bakery",
                SellingLevel.APPLY, List.of(), "", "", "approval", "FOOD_SERVICE", Set.of(), false, 30, true, "SEED",
                NOW, null, 0);
        SellingStatus s = SellingEvaluator.evaluate(facts("bakery", null), List.of(bakery, food), Map.of(), Map.of(), NOW);
        assertThat(s.missing()).hasSize(1);
        assertThat(s.missing().getFirst().scope()).isEqualTo(SellingScope.capability("FOOD_SERVICE"));
        SellingApproval granted = approved(food.scope(), NOW.plusSeconds(86400L * 100));
        assertThat(SellingEvaluator.evaluate(facts("bakery", null), List.of(bakery, food), Map.of(food.scope(), granted),
                Map.of(), NOW).level()).isEqualTo(SellingLevel.SELL);
    }

    @Test
    void brandsCompareWithoutCaseSpacesOrPunctuation() {
        assertThat(SellingScope.brand("Levi's")).isEqualTo(SellingScope.brand("LEVIS"));
        assertThat(SellingScope.brandInCategory("Louis Vuitton", "Bags-Purses").key()).isEqualTo("louisvuitton|bags-purses");
        assertThat(SellingScope.validKey(SellingScopeType.BRAND, "nike")).isTrue();
        assertThat(SellingScope.validKey(SellingScopeType.BRAND, "Nike!")).isFalse();
        assertThatThrownBy(() -> new SellingScope(SellingScopeType.BRAND, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
