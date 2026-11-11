package com.afrotransact.catalog.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ListingDocument;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import org.junit.jupiter.api.Test;

/** "Hidden" is applied the same way for every client: the answer is ignored, never stored. */
class ListingNormaliserTest {

    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/a.jpg", 800, 800, 90_000, "image/jpeg");
    private static final PackageDimensions BOX = new PackageDimensions(10, 8, 4, 2);

    private static CategoryRules rules(String code) {
        return RulesFixtures.book().forCategory(code).orElseThrow();
    }

    private static ListingDetails details(ItemCondition condition) {
        return new ListingDetails("A description", condition, List.of(), null, null, 2, null);
    }

    private static ListingNormaliser.Result apply(String code, Map<String, String> attrs, List<ProductImage> images,
                                                  List<VariantDraft> variants, ListingDetails details) {
        return ListingNormaliser.apply(rules(code), attrs, images, variants, details);
    }

    private static VariantDraft variant() {
        return new VariantDraft(Map.of(), "SKU-1", "4006381333931", false, 999, 5);
    }

    @Test
    void conditionIsFixedToNewWhereItIsHiddenAndKeptWhereTheSellerChooses() {
        assertThat(apply("packaged-snacks", Map.of(), List.of(IMAGE), List.of(variant()), details(ItemCondition.USED_GOOD))
                .details().condition()).isEqualTo(ItemCondition.NEW);
        assertThat(apply("phones", Map.of(), List.of(IMAGE), List.of(variant()), details(ItemCondition.RENEWED))
                .details().condition()).isEqualTo(ItemCondition.RENEWED);
        assertThat(apply("printed-books", Map.of(), List.of(IMAGE), List.of(variant()), details(ItemCondition.USED_GOOD))
                .details().condition()).isEqualTo(ItemCondition.USED_GOOD);
        assertThat(apply("toys-games", Map.of(), List.of(IMAGE), List.of(variant()), details(ItemCondition.USED_GOOD))
                .details().condition()).isEqualTo(ItemCondition.USED_GOOD);
    }

    @Test
    void carSeatsAreNewOnlyEvenThoughToysChooseTheCondition() {
        assertThat(apply("car-seats", Map.of(), List.of(IMAGE), List.of(variant()), details(ItemCondition.USED_GOOD))
                .details().condition()).isEqualTo(ItemCondition.NEW);
    }

    @Test
    void clothingJewelryBeautyAndFoodAreAlwaysNew() {
        for (String code : List.of("adult-clothing-footwear", "jewelry-accessories", "skincare-cosmetics",
                "dairy-eggs", "baby-bottles", "pet-food", "downloadable-ebooks", "gift-cards")) {
            assertThat(apply(code, Map.of(), List.of(IMAGE), List.of(variant()), details(ItemCondition.USED_GOOD))
                    .details().condition()).as(code).isEqualTo(ItemCondition.NEW);
        }
    }

    @Test
    void answersToHiddenQuestionsAreDropped() {
        ListingNormaliser.Result r = apply("beans-pantry-sauces", Map.of("ingredients", "Beans", "material", "Tin",
                "model", "X", "brand", "Mama", "origin", "Nigeria", "inci", "water"), List.of(IMAGE), List.of(variant()),
                details(ItemCondition.NEW));

        assertThat(r.attributes()).containsOnlyKeys("brand", "origin");
    }

    @Test
    void foodServiceHasNoBrandOriginOrBarcode() {
        ListingNormaliser.Result r = apply("prepared-meals", Map.of("brand", "Mama", "brand_id", "x", "origin", "Nigeria",
                "ingredients", "Rice"), List.of(IMAGE), List.of(variant()), details(ItemCondition.NEW));

        assertThat(r.attributes()).containsOnlyKeys("ingredients");
        assertThat(r.variants().getFirst().gtin()).isNull();
        assertThat(r.variants().getFirst().gtinExempt()).isTrue();
    }

    @Test
    void digitalProductsAndServicesDropThePackageAndTheDangerousGoodsAnswer() {
        ListingDetails withPackage = new ListingDetails("d", ItemCondition.NEW, List.of(), null, BOX, 2, null)
                .withRuleAnswers(true, List.of(), Map.of());
        ListingNormaliser.Result r = apply("downloadable-ebooks", Map.of(), List.of(IMAGE),
                List.of(variant().withPackaging(BOX)), withPackage);

        assertThat(r.variants().getFirst().packaging()).isNull();
        assertThat(r.details().packaging()).isNull();
        assertThat(r.details().dangerousGoods()).isNull();
    }

    @Test
    void dangerousGoodsIsKeptWhereAskedAndMarksTheListing() {
        ListingDetails yes = details(ItemCondition.NEW).withRuleAnswers(true, List.of(), Map.of());

        assertThat(apply("perfumes-fragrances", Map.of(), List.of(IMAGE), List.of(variant()), yes).details()
                .dangerousGoods()).isTrue();
        assertThat(apply("beans-pantry-sauces", Map.of(), List.of(IMAGE), List.of(variant()), yes).details()
                .dangerousGoods()).isNull();
    }

    @Test
    void everyVariantWithoutItsOwnPackageStartsFromTheListingPackage() {
        ListingDetails withDefault = details(ItemCondition.NEW).withPackaging(BOX);
        PackageDimensions own = new PackageDimensions(20, 12, 6, 3);
        ListingNormaliser.Result r = apply("sports-goods", Map.of(), List.of(IMAGE),
                List.of(variant().withPackaging(own), new VariantDraft(Map.of("Size", "M"), "SKU-2", "4006381333931",
                        false, 999, 5)), withDefault);

        assertThat(r.variants().get(0).packaging()).isEqualTo(own);
        assertThat(r.variants().get(1).packaging()).isEqualTo(BOX);
        assertThat(r.details().packaging()).isEqualTo(own);
    }

    @Test
    void theLabelPhotoIsKeptLastAndDroppedWhereHidden() {
        ProductImage label = IMAGE.asLabel();
        ProductImage other = new ProductImage("https://utfs.io/f/b.jpg", 800, 800, 90_000, "image/jpeg");

        assertThat(apply("packaged-snacks", Map.of(), List.of(label, IMAGE, other), List.of(variant()),
                details(ItemCondition.NEW)).images()).containsExactly(IMAGE, other, label);
        assertThat(apply("phones", Map.of(), List.of(IMAGE, label), List.of(variant()), details(ItemCondition.NEW))
                .images()).containsExactly(IMAGE);
    }

    @Test
    void documentsAndReasonsOfHiddenFieldsAreDropped() {
        ListingDocument permit = new ListingDocument("permit", "https://utfs.io/f/p.pdf", "permit.pdf", "application/pdf", 10, null);
        ListingDocument invoice = new ListingDocument("authenticity_invoice", "https://utfs.io/f/i.pdf", "i.pdf", "application/pdf", 10, null);
        ListingDetails d = details(ItemCondition.NEW).withRuleAnswers(null, List.of(permit, invoice),
                Map.of("material", "n/a", "model_number", "n/a"));

        ListingDetails kept = apply("prepared-meals", Map.of(), List.of(IMAGE), List.of(variant()), d).details();

        assertThat(kept.documents()).containsExactly(permit);
        assertThat(kept.exemptions()).isEmpty();
        assertThat(apply("bedding-linens", Map.of(), List.of(IMAGE), List.of(variant()), d).details().exemptions())
                .containsOnlyKeys("material", "model_number");
    }
}
