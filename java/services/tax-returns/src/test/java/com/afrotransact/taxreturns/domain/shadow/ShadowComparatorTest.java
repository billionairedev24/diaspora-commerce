package com.afrotransact.taxreturns.domain.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import org.junit.jupiter.api.Test;

class ShadowComparatorTest {

    private static final Instant AT = Instant.parse("2026-11-09T12:00:00Z");

    private static TaxLineInput line(boolean exempt) {
        return new TaxLineInput("l1", "AT1", "org", TaxCategory.GENERAL, 1, 10_000, 0, 0, "AT.X.Y", null,
                Map.of("STRIPE", "txcd_1"), exempt);
    }

    private static TaxLineResult result(long tax, String rate) {
        return new TaxLineResult("l1", "org", TaxCategory.GENERAL, 10_000, new BigDecimal(rate), tax, "US-TX", null,
                CollectedBy.MARKETPLACE);
    }

    private static ShadowRow compare(boolean exempt, TaxLineResult ours, TaxLineResult theirs, String error) {
        return ShadowComparator.compare(AT, "order-1", "TX", "nuts-seeds", "USD", TaxProviderId.STRIPE, line(exempt), ours,
                theirs, error);
    }

    @Test
    void sameOutcomeAndAmountIsAMatch() {
        ShadowRow r = compare(false, result(825, "0.0825"), result(825, "0.0825"), null);
        assertThat(r.kind()).isEqualTo(ShadowKind.MATCH);
        assertThat(r.differenceMinor()).isZero();
        assertThat(r.providerCode()).isEqualTo("txcd_1");
        assertThat(r.internalTaxCode()).isEqualTo("AT.X.Y");
    }

    @Test
    void aProviderTaxingWhatWeCallExemptIsAnOutcomeDifference() {
        ShadowRow r = compare(true, null, result(625, "0.0625"), null);
        assertThat(r.kind()).isEqualTo(ShadowKind.OUTCOME);
        assertThat(r.inHouseOutcome().name()).isEqualTo("EXEMPT");
        assertThat(r.providerOutcome().name()).isEqualTo("TAXABLE");
        assertThat(r.differenceMinor()).isEqualTo(625);
    }

    @Test
    void aProviderNotTaxingWhatWeTaxIsAnOutcomeDifferenceWithANegativeAmount() {
        ShadowRow r = compare(false, result(825, "0.0825"), result(0, "0"), null);
        assertThat(r.kind()).isEqualTo(ShadowKind.OUTCOME);
        assertThat(r.differenceMinor()).isEqualTo(-825);
    }

    @Test
    void theSameOutcomeAtAnotherRateIsARateDifference() {
        ShadowRow r = compare(false, result(825, "0.0825"), result(625, "0.0625"), null);
        assertThat(r.kind()).isEqualTo(ShadowKind.RATE);
        assertThat(r.differenceMinor()).isEqualTo(-200);
    }

    @Test
    void aProviderThatCouldNotAnswerIsAProviderErrorWithTheReason() {
        ShadowRow r = compare(false, result(825, "0.0825"), null, "Timed out");
        assertThat(r.kind()).isEqualTo(ShadowKind.PROVIDER_ERROR);
        assertThat(r.providerError()).isEqualTo("Timed out");
        assertThat(r.kind().differs()).isTrue();
    }

    @Test
    void aLineWeCouldNotDecideHasNothingToCompare() {
        ShadowRow r = ShadowComparator.noDecision(AT, "o", "TX", null, "USD", TaxProviderId.STRIPE, "l9", "AT9");
        assertThat(r.kind()).isEqualTo(ShadowKind.NO_DECISION);
        assertThat(r.inHouseOutcome()).isNull();
    }
}
