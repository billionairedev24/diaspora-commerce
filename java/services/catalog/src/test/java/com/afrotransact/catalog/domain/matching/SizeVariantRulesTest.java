package com.afrotransact.catalog.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.matching.SizeVariantRules.Kind;
import com.afrotransact.catalog.domain.matching.SizeVariantRules.Outcome;
import com.afrotransact.catalog.domain.matching.SizeVariantRules.PageVariant;
import org.junit.jupiter.api.Test;

class SizeVariantRulesTest {

    private static final List<PageVariant> ONE = List.of(new PageVariant("AT00000001", Map.of()));

    @Test
    void aSizeTheSinglePageDoesNotHaveIsNewAndNamesTheSizeThePageHas() {
        Outcome o = SizeVariantRules.decide("1 kg", "5 kg", "", ONE);
        assertThat(o.kind()).isEqualTo(Kind.NEW_SIZE);
        assertThat(o.label()).isEqualTo("1 kg");
        assertThat(o.dimension()).isEqualTo("Size");
        assertThat(o.pageLabel()).isEqualTo("5 kg");
    }

    @Test
    void theSameSizeInOtherUnitsIsAlreadyOnThePage() {
        Outcome o = SizeVariantRules.decide("5000g", "5 kg", null, ONE);
        assertThat(o.kind()).isEqualTo(Kind.ALREADY_ON_PAGE);
        assertThat(o.existingAtin()).isEqualTo("AT00000001");
    }

    @Test
    void aTextThatIsNotJustASizeIsInvalid() {
        assertThat(SizeVariantRules.decide("Tilda basmati 1 kg", "5 kg", "", ONE).kind()).isEqualTo(Kind.SIZE_INVALID);
        assertThat(SizeVariantRules.decide("big", "5 kg", "", ONE).kind()).isEqualTo(Kind.SIZE_INVALID);
        assertThat(SizeVariantRules.decide("  ", "5 kg", "", ONE).kind()).isEqualTo(Kind.SIZE_INVALID);
        assertThat(SizeVariantRules.decide(null, "5 kg", "", ONE).kind()).isEqualTo(Kind.SIZE_INVALID);
    }

    @Test
    void aPageThatStatesNoSizeHasNoSizeToDifferFrom() {
        assertThat(SizeVariantRules.decide("1 kg", null, "", ONE).kind()).isEqualTo(Kind.PAGE_SIZE_UNKNOWN);
    }

    @Test
    void aPackIsASize() {
        Outcome o = SizeVariantRules.decide("12 pack", "6 pack", "", ONE);
        assertThat(o.kind()).isEqualTo(Kind.NEW_SIZE);
        assertThat(o.label()).isEqualTo("12 pack");
    }

    @Test
    void aPageThatAlreadyVariesBySizeTakesTheNewSizeInItsOwnDimension() {
        List<PageVariant> variants = List.of(new PageVariant("AT00000001", Map.of("Size", "5 kg")),
                new PageVariant("AT00000002", Map.of("Size", "10 kg")));
        Outcome fresh = SizeVariantRules.decide("1 kg", "5 kg", "Size", variants);
        assertThat(fresh.kind()).isEqualTo(Kind.NEW_SIZE);
        assertThat(fresh.dimension()).isEqualTo("Size");
        Outcome have = SizeVariantRules.decide("10000 g", "5 kg", "Size", variants);
        assertThat(have.kind()).isEqualTo(Kind.ALREADY_ON_PAGE);
        assertThat(have.existingAtin()).isEqualTo("AT00000002");
    }

    @Test
    void aPageThatVariesByColourOrByTwoThingsNeedsTheOwnerOrStaff() {
        List<PageVariant> colour = List.of(new PageVariant("AT00000001", Map.of("Color", "Red")));
        assertThat(SizeVariantRules.decide("1 kg", "5 kg", "Color", colour).kind()).isEqualTo(Kind.THEME_UNSUPPORTED);
        List<PageVariant> two = List.of(new PageVariant("AT00000001", Map.of("Size", "M", "Color", "Red")));
        assertThat(SizeVariantRules.decide("1 kg", "5 kg", "Size × Color", two).kind()).isEqualTo(Kind.THEME_UNSUPPORTED);
    }

    @Test
    void aPageWithoutVariantsCannotTakeOne() {
        assertThat(SizeVariantRules.decide("1 kg", "5 kg", "", List.of()).kind()).isEqualTo(Kind.THEME_UNSUPPORTED);
    }
}
