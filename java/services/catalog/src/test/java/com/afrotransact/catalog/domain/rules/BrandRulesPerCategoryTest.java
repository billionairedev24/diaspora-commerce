package com.afrotransact.catalog.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.afrotransact.catalog.adapter.out.rules.ClasspathRulesRegistry;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.brand.UnbrandedReason;
import org.junit.jupiter.api.Test;

/**
 * The brand decision of every one of the 73 categories (docs/engineering/listing-rules.md, D23), written out here so a
 * change to the rules source cannot silently make groceries ask for a brand again or let electronics skip it.
 */
class BrandRulesPerCategoryTest {

    private static final RuleBook BOOK = new ClasspathRulesRegistry().book();
    private static final FieldStatus R = FieldStatus.REQUIRED;
    private static final FieldStatus RE = FieldStatus.REQUIRED_OR_EXEMPTION;
    private static final FieldStatus O = FieldStatus.OPTIONAL;
    private static final FieldStatus H = FieldStatus.HIDDEN;

    private static Map<String, FieldStatus> expected() {
        Map<String, FieldStatus> m = new TreeMap<>();
        // Groceries and food: a brand is optional (loose, local, store-brand and bulk goods have none).
        for (String c : List.of("beans-pantry-sauces", "candy-gum", "cooking-oils", "flours-starches", "nuts-seeds",
                "packaged-snacks", "pasta-noodles", "rice-grains", "spices-seasonings", "dairy-eggs", "fresh-produce",
                "frozen-meals", "meat-poultry-fish", "coffee-tea-dry", "energy-protein-drink-mixes",
                "flavored-water-soda", "fruit-vegetable-juice", "plain-water", "ready-to-drink-coffee-tea")) {
            m.put(c, O);
        }
        // Prepared food and services: the seller is the maker, there is no brand question.
        for (String c : List.of("bakery-products", "prepared-meals", "event-tickets", "fitness-classes",
                "hair-beauty-appointments", "laundry-dry-cleaning", "photography-media", "repairs",
                "residential-cleaning", "tutoring-consulting", "printed-books", "magazines")) {
            m.put(c, H);
        }
        // Regulated or safety-critical: the brand is the responsible manufacturer and must be named.
        for (String c : List.of("baby-food-formula", "baby-bottles", "perfumes-fragrances", "dietary-supplements",
                "medicated-skincare", "medical-devices", "otc-medicines", "first-aid-wound-care", "computers-laptops",
                "gaming", "phones", "tvs-audio-accessories", "toys-games", "car-seats", "gift-cards")) {
            m.put(c, R);
        }
        // Branded goods that are also made by hand or by a tailor: a brand, or "No brand" or "Handmade".
        for (String c : List.of("haircare", "skincare-cosmetics", "feminine-hygiene", "personal-hygiene",
                "adult-clothing-footwear", "bags-purses", "childrens-clothing", "jewelry-accessories",
                "cleaning-paper-products", "furniture-small-appliances", "kitchenware-cookware", "diapers-wipes")) {
            m.put(c, RE);
        }
        // Mostly unbranded or handmade goods, and digital products: optional.
        for (String c : List.of("hair-accessories", "wigs-extensions", "bedding-linens", "decor-candles",
                "pet-grooming-accessories", "pet-food", "handmade-products", "school-office-supplies", "sports-goods",
                "gift-baskets-bundles", "audiobooks", "digital-art-downloads", "downloadable-ebooks",
                "downloadable-software", "online-courses")) {
            m.put(c, O);
        }
        return m;
    }

    @Test
    void everyOneOfThe73CategoriesHasItsDecision() {
        Map<String, FieldStatus> expected = expected();

        assertThat(expected).hasSize(73);
        Map<String, FieldStatus> actual = new TreeMap<>();
        BOOK.categories().values().stream().filter(c -> !c.provisional())
                .forEach(c -> actual.put(c.code(), c.status(RuleField.BRAND)));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void groceriesNeverAskForABrandAndTheFormMayLeaveItEmptyOrChooseNoBrand() {
        for (String code : List.of("rice-grains", "fresh-produce", "spices-seasonings", "plain-water")) {
            AttributeDefinition brand = brandOf(code);

            assertThat(brand.required()).as(code).isFalse();
            assertThat(brand.problem(null)).as(code).isNull();
            assertThat(brand.unbrandedReasons()).as(code).contains(UnbrandedReason.UNBRANDED);
        }
    }

    @Test
    void aBrandOrReasonCategoryRequiresAnAnswerButOffersNoBrandAndHandmade() {
        AttributeDefinition brand = brandOf("adult-clothing-footwear");

        assertThat(brand.required()).isTrue();
        assertThat(brand.problem(null)).isNotNull();
        assertThat(brand.unbrandedReasons()).containsExactlyInAnyOrder(UnbrandedReason.UNBRANDED,
                UnbrandedReason.HANDMADE);
    }

    @Test
    void aRequiredBrandMustBeNamedSoNoBrandIsNotOffered() {
        for (String code : List.of("phones", "baby-food-formula", "perfumes-fragrances", "dietary-supplements",
                "toys-games")) {
            AttributeDefinition brand = brandOf(code);

            assertThat(brand.required()).as(code).isTrue();
            assertThat(brand.unbrandedReasons()).as(code).isEmpty();
        }
    }

    @Test
    void booksFoodServiceAndServicesHaveNoBrandQuestionAtAll() {
        for (String code : List.of("printed-books", "magazines", "prepared-meals", "bakery-products", "repairs")) {
            ProductType type = RuleAwareType.apply(new ProductType("t", "t", List.of(), List.of(), IdentifierRule.GTIN),
                    BOOK.forCategory(code).orElseThrow());

            assertThat(type.brandAttribute()).as(code).isEmpty();
        }
    }

    @Test
    void everyBrandGatedCategoryStillNamesItsBrandOrAllowsAnUnbrandedItem() {
        // Gating is by brand name (selling rules); an unbranded item matches no gated brand, so it is never gated.
        BOOK.categories().values().stream().filter(c -> "brand_gated".equals(c.approval())).forEach(c ->
                assertThat(c.status(RuleField.BRAND)).as(c.code()).isIn(R, RE));
    }

    private static AttributeDefinition brandOf(String code) {
        ProductType type = RuleAwareType.apply(new ProductType("t", "t", List.of(), List.of(), IdentifierRule.GTIN),
                BOOK.forCategory(code).orElseThrow());
        return type.brandAttribute().orElseThrow();
    }
}
