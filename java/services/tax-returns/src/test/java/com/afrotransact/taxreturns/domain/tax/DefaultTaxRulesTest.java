package com.afrotransact.taxreturns.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DefaultTaxRulesTest {

    private static final StateTaxRule TX = new StateTaxRule("US", "TX", "Texas", new BigDecimal("0.0625"),
            BigDecimal.ZERO, new BigDecimal("0.0625"), true, true, 0);
    private static final StateTaxRule CA = new StateTaxRule("US", "CA", "California", new BigDecimal("0.0725"),
            BigDecimal.ZERO, new BigDecimal("0.0725"), false, true, 0);
    private static final StateTaxRule IL = new StateTaxRule("US", "IL", "Illinois", new BigDecimal("0.0625"),
            new BigDecimal("0.01"), new BigDecimal("0.0625"), false, true, 0);
    private static final StateTaxRule DE = new StateTaxRule("US", "DE", "Delaware", BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, false, false, 0);
    private static final StateTaxRule MT = new StateTaxRule("US", "MT", "Montana (not facilitator)",
            new BigDecimal("0.05"), BigDecimal.ZERO, new BigDecimal("0.05"), false, false, 0);
    private static final TaxRateTable TABLE = new TaxRateTable(List.of(TX, CA, IL, DE, MT), List.of(
            new LocalTaxRate("TX", "770", "Houston, TX", new BigDecimal("0.02"), false),
            new LocalTaxRate("TX", "77002", "Downtown Houston, TX", new BigDecimal("0.0225"), false),
            new LocalTaxRate("CA", "900", "Los Angeles, CA", new BigDecimal("0.025"), false),
            new LocalTaxRate("IL", "606", "Chicago, IL", new BigDecimal("0.04"), true)));

    private final DefaultTaxRules rules = new DefaultTaxRules();

    private static TaxLineInput line(TaxCategory category, int qty, long unit) {
        return new TaxLineInput("l1", "AT00000001", "org_a", category, qty, unit, 0, 0);
    }

    private TaxLineResult one(TaxAddress to, TaxLineInput in) {
        return rules.quote(TABLE, to, "USD", List.of(in)).lines().getFirst();
    }

    @Test
    void stateRatePlusZipPrefixLocalRate() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "77019", "Houston"), line(TaxCategory.GENERAL, 1, 10_000));
        assertThat(r.rate()).isEqualByComparingTo("0.0825");
        assertThat(r.taxMinor()).isEqualTo(825);
        assertThat(r.jurisdiction()).isEqualTo("US-TX");
        assertThat(r.localJurisdiction()).isEqualTo("US-TX-770");
        assertThat(r.collectedBy()).isEqualTo(CollectedBy.MARKETPLACE);
    }

    @Test
    void exactZipWinsOverPrefixAndZipPlusFourIsCut() {
        TaxLineResult r = one(new TaxAddress("us", "tx", "77002-1234", null), line(TaxCategory.GENERAL, 1, 10_000));
        assertThat(r.rate()).isEqualByComparingTo("0.085");
        assertThat(r.localJurisdiction()).isEqualTo("US-TX-77002");
        assertThat(r.taxMinor()).isEqualTo(850);
    }

    @Test
    void stateOnlyWhenNoLocalRate() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "78701", null), line(TaxCategory.GENERAL, 2, 1_999));
        // 3998 × 0.0625 = 249.875 → 250
        assertThat(r.taxMinor()).isEqualTo(250);
        assertThat(r.localJurisdiction()).isNull();
    }

    @ParameterizedTest(name = "{0} × {1} at {2} → {3}")
    @CsvSource({
        "1, 200, 0.0625, 13",
        "1, 199, 0.0625, 12",
        "3, 333, 0.0825, 82",
        "1, 1999, 0.0825, 165",
        "1, 10000, 0.08875, 888",
        "1, 1, 0.0825, 0",
        "1, 7, 0.0725, 1",
        "1, 6, 0.0825, 0",
        "1, 0, 0.0825, 0"
    })
    void halfUpPerLine(int qty, long unit, String rate, long expected) {
        assertThat(DefaultTaxRules.taxOf(qty * unit, new BigDecimal(rate))).isEqualTo(expected);
    }

    @Test
    void roundsOncePerLineNotPerUnit() {
        // per unit: 7 × 0.0725 = 0.5075 → 1 each → 3; per line: 21 × 0.0725 = 1.5225 → 2
        TaxLineResult r = one(new TaxAddress("US", "CA", "94105", null), line(TaxCategory.GENERAL, 3, 7));
        assertThat(r.taxMinor()).isEqualTo(2);
    }

    @Test
    void orderTaxIsTheSumOfLineTaxes() {
        TaxQuote q = rules.quote(TABLE, new TaxAddress("US", "TX", "77019", null), "USD", List.of(
                new TaxLineInput("a", null, "org_a", TaxCategory.GENERAL, 1, 1_999, 0, 0),
                new TaxLineInput("b", null, "org_b", TaxCategory.GENERAL, 1, 1_999, 0, 0),
                new TaxLineInput("c", null, "org_b", TaxCategory.GROCERY, 4, 899, 0, 0)));
        assertThat(q.lines()).extracting(TaxLineResult::taxMinor).containsExactly(165L, 165L, 0L);
        assertThat(q.totalTaxMinor()).isEqualTo(330);
        assertThat(q.lines()).extracting(TaxLineResult::sellerOrgId).containsExactly("org_a", "org_b", "org_b");
    }

    @Test
    void groceryExemptStateAndLocal() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "77019", null), line(TaxCategory.GROCERY, 2, 1_850));
        assertThat(r.taxMinor()).isZero();
        assertThat(r.rate()).isEqualByComparingTo("0");
        assertThat(r.taxableMinor()).isZero();
        assertThat(r.collectedBy()).isEqualTo(CollectedBy.MARKETPLACE);
    }

    @Test
    void groceryReducedRateWithLocalThatAppliesToGrocery() {
        // IL grocery 1 % + Chicago local 4 % (applies to grocery in this table)
        TaxLineResult r = one(new TaxAddress("US", "IL", "60601", null), line(TaxCategory.GROCERY, 1, 1_000));
        assertThat(r.rate()).isEqualByComparingTo("0.05");
        assertThat(r.taxMinor()).isEqualTo(50);
    }

    @Test
    void preparedFoodTaxedAtItsRate() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "77019", null), line(TaxCategory.PREPARED_FOOD, 1, 1_200));
        assertThat(r.taxMinor()).isEqualTo(99);
    }

    @Test
    void discountReducesTheTaxableAmount() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "78701", null),
                new TaxLineInput("l1", null, "org_a", TaxCategory.GENERAL, 2, 5_000, 1_000, 0));
        assertThat(r.taxableMinor()).isEqualTo(9_000);
        assertThat(r.taxMinor()).isEqualTo(563);
    }

    @Test
    void discountNeverMakesTaxNegative() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "78701", null),
                new TaxLineInput("l1", null, "org_a", TaxCategory.GENERAL, 1, 500, 900, 0));
        assertThat(r.taxMinor()).isZero();
    }

    @Test
    void shippingTaxedOnlyWhereStateTaxesItAndOnlyForTaxableItems() {
        TaxLineInput withShipping = new TaxLineInput("l1", null, "org_a", TaxCategory.GENERAL, 1, 10_000, 0, 1_000);
        assertThat(one(new TaxAddress("US", "TX", "78701", null), withShipping).taxMinor()).isEqualTo(688);
        assertThat(one(new TaxAddress("US", "TX", "78701", null), withShipping).shippingTaxMinor()).isEqualTo(63);
        assertThat(one(new TaxAddress("US", "CA", "94105", null), withShipping).taxMinor()).isEqualTo(725);
        assertThat(one(new TaxAddress("US", "CA", "94105", null), withShipping).shippingTaxMinor()).isZero();
        TaxLineInput grocery = new TaxLineInput("l2", null, "org_a", TaxCategory.GROCERY, 1, 10_000, 0, 1_000);
        assertThat(one(new TaxAddress("US", "TX", "78701", null), grocery).taxMinor()).isZero();
    }

    @Test
    void nonFacilitatorStatesUnknownStatesAndAbroadAreNotCollected() {
        for (TaxAddress to : List.of(new TaxAddress("US", "MT", "59601", null), new TaxAddress("US", "DE", "19901",
                null), new TaxAddress("US", "ZZ", "00000", null), new TaxAddress("GB", "", "SW1A 1AA", "London"))) {
            TaxQuote q = rules.quote(TABLE, to, "USD", List.of(line(TaxCategory.GENERAL, 1, 10_000)));
            assertThat(q.collectedBy()).as(to.toString()).isEqualTo(CollectedBy.NOT_COLLECTED);
            assertThat(q.totalTaxMinor()).isZero();
        }
        assertThat(new TaxAddress("GB", "", "SW1A", null).stateJurisdiction()).isEqualTo("GB");
        assertThat(new TaxAddress(null, "ny", "10001", null).stateJurisdiction()).isEqualTo("US-NY");
    }

    @Test
    void largeAmountsStayExact() {
        TaxLineResult r = one(new TaxAddress("US", "TX", "77019", null), line(TaxCategory.GENERAL, 1_000,
                99_999_999L));
        assertThat(r.taxMinor()).isEqualTo(8_249_999_918L);
    }

    @Test
    void categoriesParseLeniently() {
        assertThat(TaxCategory.parse("grocery")).isEqualTo(TaxCategory.GROCERY);
        assertThat(TaxCategory.parse("prepared-food")).isEqualTo(TaxCategory.PREPARED_FOOD);
        assertThat(TaxCategory.parse("luxury")).isEqualTo(TaxCategory.GENERAL);
        assertThat(TaxCategory.parse(null)).isEqualTo(TaxCategory.GENERAL);
    }

    @Test
    void rejectsInvalidRatesAndLines() {
        assertThatThrownBy(() -> new StateTaxRule("US", "TX", "x", new BigDecimal("1.2"), BigDecimal.ZERO,
                BigDecimal.ZERO, false, true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalTaxRate("TX", "77", "x", BigDecimal.ZERO, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line(TaxCategory.GENERAL, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line(TaxCategory.GENERAL, 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
