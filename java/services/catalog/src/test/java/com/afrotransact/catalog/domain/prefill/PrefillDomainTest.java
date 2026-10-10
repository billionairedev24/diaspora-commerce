package com.afrotransact.catalog.domain.prefill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** S031: merging provider answers and mapping provider categories onto our taxonomy. */
class PrefillDomainTest {

    private final CategoryHintPolicy hints = new DefaultCategoryHintPolicy();

    @Test
    void openFoodFactsTagsMapToTheMostSpecificCategoryFirst() {
        List<CategoryHintPolicy.Match> m = hints.match(List.of("en:plant-based-foods-and-beverages",
                "en:plant-based-foods", "en:cereals-and-potatoes", "en:cereals-and-their-products", "en:rices",
                "en:long-grain-rices"), 3);
        assertThat(m).extracting(CategoryHintPolicy.Match::path).containsExactly("Groceries › Pantry › Grains & rice");
        assertThat(m.getFirst().matchedOn()).isEqualTo("en:long-grain-rices");

        assertThat(hints.match(List.of("en:groceries", "en:sauces", "en:tomato-pastes"), 3))
                .extracting(CategoryHintPolicy.Match::path).containsExactly("Groceries › Pantry › Sauces & pastes");
        assertThat(hints.match(List.of("en:beverages", "en:hot-beverages", "en:herbal-teas"), 3))
                .extracting(CategoryHintPolicy.Match::path)
                .containsExactly("Groceries › Drinks › Teas & hibiscus", "Health › Wellness › Herbal teas & remedies");
        assertThat(hints.match(List.of("en:snacks", "en:salty-snacks", "en:plantain-chips"), 1))
                .extracting(CategoryHintPolicy.Match::path).containsExactly("Groceries › Pantry › Snacks");
    }

    @Test
    void gs1GpcSegmentsGiveADepartmentAndUnknownTagsNothing() {
        assertThat(hints.match(List.of("gpc:10000043"), 3)).isEmpty();
        assertThat(hints.match(List.of("gpc:50202300"), 3)).extracting(CategoryHintPolicy.Match::path)
                .containsExactly("Groceries");
        assertThat(hints.match(List.of("en:frozen-pizzas-and-pies"), 3)).isEmpty();
        assertThat(hints.match(List.of(), 3)).isEmpty();
    }

    @Test
    void mergeTakesEachFieldFromTheFirstProviderThatHasIt() {
        ProductData gs1 = new ProductData("GS1", "09501101530003", "Mama Gold Rice 5 lb", "Mama Gold", "5 lb",
                null, List.of(), List.of("gpc:50000000"));
        ProductData off = new ProductData("OPEN_FOOD_FACTS", "9501101530003", "Rice", "Other brand", "2.27 kg",
                "Long grain parboiled rice", List.of("https://images.openfoodfacts.org/1.jpg"),
                List.of("en:rices"));
        ProductPrefill p = ProductPrefill.merge(List.of(gs1, off));
        assertThat(p.found()).isTrue();
        assertThat(p.title()).isEqualTo("Mama Gold Rice 5 lb");
        assertThat(p.brand()).isEqualTo("Mama Gold");
        assertThat(p.netContent()).isEqualTo("5 lb");
        assertThat(p.description()).isEqualTo("Long grain parboiled rice");
        assertThat(p.imageUrls()).containsExactly("https://images.openfoodfacts.org/1.jpg");
        assertThat(p.importedFrom()).containsEntry("title", "GS1").containsEntry("brand", "GS1")
                .containsEntry("description", "OPEN_FOOD_FACTS").containsEntry("imageUrls", "OPEN_FOOD_FACTS");
        assertThat(p.sources()).containsExactly("GS1", "OPEN_FOOD_FACTS");
        assertThat(p.sourceCategories()).containsExactly("gpc:50000000", "en:rices");

        assertThat(ProductPrefill.merge(List.of()).found()).isFalse();
    }

    @Test
    void providerDataIsCleaned() {
        ProductData d = new ProductData("OPEN_FOOD_FACTS", null, "  Ofada   rice ", " ", null, null,
                List.of("ftp://x", "https://a/1.jpg", "https://a/1.jpg"), null);
        assertThat(d.title()).isEqualTo("Ofada rice");
        assertThat(d.brand()).isNull();
        assertThat(d.imageUrls()).containsExactly("https://a/1.jpg");
        assertThat(new ProductData("GS1", null, null, null, null, null, null, null).empty()).isTrue();
    }
}
