package com.afrotransact.catalog.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.afrotransact.catalog.domain.matching.CandidateRanker.Kind;
import com.afrotransact.catalog.domain.matching.CandidateRanker.Page;
import com.afrotransact.catalog.domain.matching.CandidateRanker.Query;
import com.afrotransact.catalog.domain.matching.CandidateRanker.Score;
import com.afrotransact.catalog.domain.matching.PackSize.Agreement;
import org.junit.jupiter.api.Test;

class CandidateRankerTest {

    private static final MatchThresholds T = MatchThresholds.defaults();
    private static final UUID RICE = UUID.randomUUID();
    private static final UUID PASTA = UUID.randomUUID();
    private static final UUID SHOES = UUID.randomUUID();
    private static final String RICE_PATH = "Grocery › Pantry › Rice";
    private static final String PASTA_PATH = "Grocery › Pantry › Pasta";
    private static final String SHOES_PATH = "Fashion › Men › Shoes";

    private static Page page(String title, String brand, UUID category, String path, String partNumber) {
        return new Page("AT00000001", title, brand, category, path, null, partNumber);
    }

    private static Score score(String name, String brand, UUID category, String path, Page page) {
        return CandidateRanker.score(new Query(name, brand, category, path), page, T);
    }

    @Test
    void theSameNameBrandSizeAndCategoryIsExactAndScoresHigh() {
        Page p = page("Tilda Basmati Rice 5kg", "Tilda", RICE, RICE_PATH, null);
        Score s = score("tilda basmati rice 5 kg", null, RICE, RICE_PATH, p);
        assertThat(s.kind()).isEqualTo(Kind.SAME_NAME);
        assertThat(s.size()).isEqualTo(Agreement.SAME);
        assertThat(s.score()).isEqualTo(100);
    }

    @Test
    void aNameWithoutTheBrandIsOnlySimilarEvenWhenEverythingElseAgrees() {
        Page p = page("Tilda Basmati Rice 5kg", "Tilda", RICE, RICE_PATH, null);
        Score s = score("basmati rice 5kg", null, RICE, RICE_PATH, p);
        assertThat(s.kind()).isEqualTo(Kind.SIMILAR);
        assertThat(s.score()).isGreaterThanOrEqualTo(T.likelyMin());
    }

    @Test
    void aTypoOrExtraWordIsNeverExact() {
        Page p = page("Tilda Basmati Rice 5kg", "Tilda", RICE, RICE_PATH, null);
        assertThat(score("tilda basmatti rice 5kg", null, RICE, RICE_PATH, p).kind()).isEqualTo(Kind.SIMILAR);
        assertThat(score("tilda basmati long grain rice 5kg", null, RICE, RICE_PATH, p).kind()).isEqualTo(Kind.SIMILAR);
    }

    @Test
    void aDifferentSizeIsNeverLikelyAndNeverExact() {
        Page p = page("Tilda Basmati Rice 1kg", "Tilda", RICE, RICE_PATH, null);
        Score s = score("tilda basmati rice 5kg", null, RICE, RICE_PATH, p);
        assertThat(s.size()).isEqualTo(Agreement.DIFFERENT);
        assertThat(s.kind()).isEqualTo(Kind.SIMILAR);
        assertThat(s.score()).isLessThan(T.likelyMin());
    }

    @Test
    void aSizeOnOneSideOnlyIsNotExact() {
        Page p = page("Tilda Basmati Rice 5kg", "Tilda", RICE, RICE_PATH, null);
        Score s = score("tilda basmati rice", null, RICE, RICE_PATH, p);
        assertThat(s.size()).isEqualTo(Agreement.UNKNOWN);
        assertThat(s.kind()).isEqualTo(Kind.SIMILAR);
    }

    @Test
    void aDifferentBrandTakesPointsOff() {
        Page tilda = page("Basmati Rice 5kg", "Tilda", RICE, RICE_PATH, null);
        Score same = score("basmati rice 5kg", "Tilda", RICE, RICE_PATH, tilda);
        Score other = score("basmati rice 5kg", "Royal", RICE, RICE_PATH, tilda);
        assertThat(other.score()).isLessThan(same.score() - 30);
        assertThat(other.kind()).isEqualTo(Kind.SIMILAR);
    }

    @Test
    void theSameCategoryBoostsAndAnotherDepartmentCosts() {
        Page p = page("Basmati Rice 5kg", null, RICE, RICE_PATH, null);
        int same = score("basmati rice 5kg", null, RICE, RICE_PATH, p).score();
        int branch = score("basmati rice 5kg", null, PASTA, PASTA_PATH, p).score();
        int other = score("basmati rice 5kg", null, SHOES, SHOES_PATH, p).score();
        int none = score("basmati rice 5kg", null, null, null, p).score();
        assertThat(same).isGreaterThan(branch);
        assertThat(branch).isGreaterThan(none);
        assertThat(none).isGreaterThan(other);
    }

    @Test
    void aDifferentCategoryIsNeverExact() {
        Page p = page("Basmati Rice 5kg", null, RICE, RICE_PATH, null);
        assertThat(score("basmati rice 5kg", null, PASTA, PASTA_PATH, p).kind()).isEqualTo(Kind.SIMILAR);
    }

    @Test
    void aPartNumberTheSellerHappenedToTypeIsAHiddenBoost() {
        Page p = page("Rice cooker", "Zojirushi", RICE, RICE_PATH, "NS-ZCC10");
        int with = score("zojirushi rice cooker nszcc10", null, RICE, RICE_PATH, p).score();
        int without = score("zojirushi rice cooker", null, RICE, RICE_PATH, p).score();
        assertThat(with).isGreaterThan(without);
    }

    @Test
    void unrelatedNamesScoreBelowTheShowThreshold() {
        Page p = page("Leather Oxford Shoes", "Clarks", SHOES, SHOES_PATH, null);
        assertThat(score("basmati rice 5kg", null, RICE, RICE_PATH, p).score()).isLessThan(T.showMin());
    }

    @Test
    void scoresStayBetweenZeroAndOneHundred() {
        Page p = page("x", "Brand", SHOES, SHOES_PATH, null);
        int s = score("completely different thing", "Other", RICE, RICE_PATH, p).score();
        assertThat(s).isBetween(0, 100);
    }

    @Test
    void thresholdsRejectNonsense() {
        assertThatThrownBy(() -> new MatchThresholds(80, 70, 6, 30, 3, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
