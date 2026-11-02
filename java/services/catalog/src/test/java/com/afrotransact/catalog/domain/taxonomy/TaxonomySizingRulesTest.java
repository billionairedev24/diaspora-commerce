package com.afrotransact.catalog.domain.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.application.TaxonomyAdminService;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizingTrait;
import com.afrotransact.catalog.domain.sizing.SuggestedMeasurement;
import org.junit.jupiter.api.Test;

/** What staff may configure on a product type for sizing: the trait and the variation themes it rests on. */
class TaxonomySizingRulesTest {

    private static final List<VariationTheme> THEMES = List.of(
            new VariationTheme("Size × Color", List.of(new VariationDimension("Size", List.of("S", "M")),
                    new VariationDimension("Color", List.of("Black", "Gold")))),
            new VariationTheme("Color", List.of(new VariationDimension("Color", List.of("Black")))));

    private static SuggestedMeasurement bust() {
        return new SuggestedMeasurement("bust", "Bust", MeasurementUnit.CM, "Around the fullest part");
    }

    @Test
    void aSizingTraitNeedsASizeDimensionInOneOfTheThemes() {
        SizingTrait ok = TaxonomyRules.sizing(new SizingTrait(true, "Size", List.of(bust())), THEMES);

        assertThat(ok.sizeChartRequired()).isTrue();
        assertThat(ok.suggestedMeasurements()).containsExactly(bust());
        assertThatThrownBy(() -> TaxonomyRules.sizing(new SizingTrait(true, "Shoe size", List.of()), THEMES))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fields()).containsExactly("traits.sizing.sizeDimension"));
    }

    @Test
    void noSizingIsFine() {
        assertThat(TaxonomyRules.sizing(null, THEMES)).isNull();
    }

    @Test
    void measurementKeysAreDerivedFromLabelsAndMustDiffer() {
        SizingTrait derived = TaxonomyRules.sizing(new SizingTrait(false, null, List.of(
                new SuggestedMeasurement("", "Foot length", MeasurementUnit.CM, null))), THEMES);

        assertThat(derived.sizeDimension()).isEqualTo("Size");
        assertThat(derived.suggestedMeasurements().getFirst().key()).isEqualTo("foot_length");
        assertThatThrownBy(() -> TaxonomyRules.sizing(new SizingTrait(false, "Size", List.of(bust(),
                new SuggestedMeasurement("bust", "Chest", null, null), new SuggestedMeasurement("x1", "bust", null, null))),
                THEMES)).isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fields()).contains(
                        "traits.sizing.suggestedMeasurements[1].key", "traits.sizing.suggestedMeasurements[2].label"));
    }

    @Test
    void atMostTwelveSuggestedMeasurements() {
        List<SuggestedMeasurement> many = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            many.add(new SuggestedMeasurement("m" + i + "x", "Measure " + i, null, null));
        }

        assertThatThrownBy(() -> TaxonomyRules.sizing(new SizingTrait(false, "Size", many), THEMES))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fields()).contains("traits.sizing.suggestedMeasurements"));
    }

    @Test
    void aSizedTypeLetsSellersWriteTheirOwnSizesButNotOtherDimensions() {
        List<VariationTheme> themes = TaxonomyRules.withCustomSizes(THEMES, new SizingTrait(false, "Size", List.of()));

        assertThat(themes.getFirst().dimension("Size").allowCustomValues()).isTrue();
        assertThat(themes.getFirst().dimension("Color").allowCustomValues()).isFalse();
        assertThat(TaxonomyRules.withCustomSizes(THEMES, null)).isSameAs(THEMES);
    }

    @Test
    void themesStaffEditAreTidiedAndChecked() {
        List<VariationTheme> ok = TaxonomyRules.themes(List.of(new VariationTheme(" Size ", List.of(
                new VariationDimension(" Size ", List.of(" S ", "M", ""), true)))));

        assertThat(ok.getFirst().name()).isEqualTo("Size");
        assertThat(ok.getFirst().dimensions().getFirst().options()).containsExactly("S", "M");
        assertThat(ok.getFirst().dimensions().getFirst().allowCustomValues()).isTrue();
    }

    @Test
    void duplicateThemesDimensionsAndOptionsAreRefused() {
        assertThatThrownBy(() -> TaxonomyRules.themes(List.of(
                new VariationTheme("Size", List.of(new VariationDimension("Size", List.of("S", "s")))),
                new VariationTheme("size", List.of(new VariationDimension("Size", List.of("S")))))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fields())
                        .contains("themes[0].dimensions[0].options", "themes[1].name"));
        assertThatThrownBy(() -> TaxonomyRules.themes(List.of(new VariationTheme("Size", List.of(
                new VariationDimension("Size", List.of("S")), new VariationDimension("size", List.of("M")))))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fields()).contains("themes[0].dimensions[1].name"));
    }

    @Test
    void aDimensionWithoutOptionsNeedsToAllowCustomValues() {
        assertThatThrownBy(() -> TaxonomyRules.themes(List.of(new VariationTheme("Size", List.of(
                new VariationDimension("Size", List.of()))))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fields()).contains("themes[0].dimensions[0].options"));
        assertThat(TaxonomyRules.themes(List.of(new VariationTheme("Size", List.of(
                new VariationDimension("Size", List.of(), true)))))).hasSize(1);
    }

    @Test
    void aSizingEditChangesOnlyTheFieldsItSends() {
        SizingTrait current = new SizingTrait(false, "Size", List.of(bust()));

        SizingTrait required = new TaxonomyAdminService.SizingEdit(false, true, null, null).applyTo(current);
        SizingTrait renamed = new TaxonomyAdminService.SizingEdit(false, null, null, List.of()).applyTo(current);
        SizingTrait fresh = new TaxonomyAdminService.SizingEdit(false, true, null, null).applyTo(null);

        assertThat(required.sizeChartRequired()).isTrue();
        assertThat(required.suggestedMeasurements()).containsExactly(bust());
        assertThat(renamed.sizeChartRequired()).isFalse();
        assertThat(renamed.suggestedMeasurements()).isEmpty();
        assertThat(fresh.sizeDimension()).isEqualTo("Size");
        assertThat(new TaxonomyAdminService.SizingEdit(true, null, null, null).applyTo(current)).isNull();
    }
}
