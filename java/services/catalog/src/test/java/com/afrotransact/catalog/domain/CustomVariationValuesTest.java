package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import org.junit.jupiter.api.Test;

/** Sellers write their own sizes and colours where the category allows it (allowCustomValues). */
class CustomVariationValuesTest {

    private static final ProductImage BIG = new ProductImage("https://utfs.io/f/a.jpg", 1200, 1200, 200_000,
            "image/jpeg");
    private final ProductDraftPolicy policy = new DefaultProductDraftPolicy();

    private static VariantDraft v(String sku, String size, String color) {
        return new VariantDraft(Map.of("Size", size, "Color", color), sku, "", true, 4900, 5);
    }

    private static VariantDraft sizeOnly(String sku, String size) {
        return new VariantDraft(Map.of("Size", size), sku, "", true, 4900, 5);
    }

    private Map<String, String> errors(ProductType type, String theme, List<VariantDraft> variants) {
        try {
            policy.validate(new ProductDraft(type, "Ankara Wrap Dress, Puff Sleeve", Map.of("brand", "Adire Lagos"),
                    List.of(BIG), theme, variants));
            return Map.of();
        } catch (CatalogRuleViolation ex) {
            return ex.fieldErrors();
        }
    }

    @Test
    void theCategorysSizesAndColoursStillWork() {
        assertThat(errors(SizingFixtures.DRESS, "Size × Color", List.of(v("A-1", "XL", "Gold"), v("A-2", "S", "Indigo")))).isEmpty();
    }

    @Test
    void aSellerMayUseSizesAndColoursOfTheirOwn() {
        assertThat(errors(SizingFixtures.DRESS, "Size × Color", List.of(v("A-1", "UK 12", "Ankara blue print"),
                v("A-2", "Free size", "Indigo adire"), v("A-3", "Age 6-7", "Black")))).isEmpty();
    }

    @Test
    void aDimensionWithoutTheFlagKeepsToItsList() {
        Map<String, String> errors = errors(SizingFixtures.SPICES, "Size", List.of(sizeOnly("S-1", "2 lb")));

        assertThat(errors).containsEntry("variants[0].options.Size", "Choose a size");
    }

    @Test
    void customValuesAreCheckedForLengthAndCharacters() {
        Map<String, String> errors = errors(SizingFixtures.DRESS, "Size", List.of(sizeOnly("A-1", "X".repeat(31)),
                sizeOnly("A-2", "<script>"), sizeOnly("A-3", "-1"), sizeOnly("A-4", " ")));

        assertThat(errors.get("variants[0].options.Size")).isEqualTo("At most 30 characters");
        assertThat(errors.get("variants[1].options.Size")).startsWith("Use letters, digits");
        assertThat(errors.get("variants[2].options.Size")).startsWith("Use letters, digits");
        assertThat(errors.get("variants[3].options.Size")).isEqualTo("Choose a size");
    }

    @Test
    void theSameValueSpelledTwoWaysOnOneProductIsRefused() {
        Map<String, String> errors = errors(SizingFixtures.DRESS, "Size", List.of(sizeOnly("A-1", "Free size"),
                sizeOnly("A-2", "free SIZE")));

        assertThat(errors).containsKey("variants[1].options.Size");
        assertThat(errors.get("variants[1].options.Size")).contains("are the same size");
    }

    @Test
    void aSuggestionMustBeWrittenAsTheCategoryWritesIt() {
        assertThat(errors(SizingFixtures.DRESS, "Size", List.of(sizeOnly("A-1", "xl"))).get("variants[0].options.Size"))
                .isEqualTo("Write it as XL");
    }

    @Test
    void aProductHasAtMostFortyDifferentCustomValuesOfADimension() {
        List<VariantDraft> variants = new ArrayList<>();
        for (int i = 1; i <= CustomValues.MAX_PER_PRODUCT + 1; i++) {
            variants.add(sizeOnly("A-" + i, "UK " + i));
        }

        Map<String, String> errors = errors(SizingFixtures.DRESS, "Size", variants);

        assertThat(errors).containsOnlyKeys("variants[" + CustomValues.MAX_PER_PRODUCT + "].options.Size");
        assertThat(errors.get("variants[" + CustomValues.MAX_PER_PRODUCT + "].options.Size")).contains("Up to 40");
    }

    @Test
    void duplicateCombinationsAreFoundIgnoringCase() {
        Map<String, String> errors = errors(SizingFixtures.DRESS, "Size × Color", List.of(v("A-1", "UK 12", "Indigo adire"),
                v("A-2", "UK 12", "indigo ADIRE")));

        assertThat(errors).containsKey("variants[1].options");
    }

    @Test
    void variantOptionsTakeTheCategorysSpellingThenTheFirstSpellingOnTheProduct() {
        List<VariantDraft> fixed = VariantOptions.canonical(SizingFixtures.DRESS, "Size × Color", List.of(
                v("A-1", " xl ", "ankara   blue print"), v("A-2", "Free size", "Ankara blue print"),
                v("A-3", "free SIZE", "GOLD")));

        assertThat(fixed.get(0).options()).containsEntry("Size", "XL").containsEntry("Color", "ankara blue print");
        assertThat(fixed.get(1).options()).containsEntry("Size", "Free size").containsEntry("Color", "ankara blue print");
        assertThat(fixed.get(2).options()).containsEntry("Size", "Free size").containsEntry("Color", "Gold");
        assertThat(errors(SizingFixtures.DRESS, "Size × Color", fixed)).isEmpty();
    }

    @Test
    void variantOptionsLeaveStrictDimensionsAlone() {
        List<VariantDraft> same = VariantOptions.canonical(SizingFixtures.SPICES, "Size", List.of(sizeOnly("S-1", "1 LB")));

        assertThat(same.getFirst().options()).containsEntry("Size", "1 LB");
    }
}
