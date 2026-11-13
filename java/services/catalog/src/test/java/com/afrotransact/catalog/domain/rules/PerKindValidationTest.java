package com.afrotransact.catalog.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.ProductDraft;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One test per kind of product: an empty listing is refused for exactly the fields the matrix requires, and a listing
 * with everything the matrix asks for is accepted. The expected keys are written out here from the approved matrix, not
 * derived from the rules, so a wrong cell in the source is caught.
 */
class PerKindValidationTest {

    private static final DefaultProductDraftPolicy POLICY = new DefaultProductDraftPolicy();
    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/a.jpg", 800, 800, 90_000, "image/jpeg");
    private static final ProductImage LABEL = IMAGE.asLabel();
    private static final PackageDimensions BOX = new PackageDimensions(10, 8, 4, 2);
    private static final String GTIN = "4006381333931";
    private static final String ISBN = "9780306406157";

    /** kind, a category of that kind, the keys an otherwise valid but empty listing is refused for. */
    static List<Arguments> kinds() {
        return List.of(
                Arguments.of("PKG", "beans-pantry-sauces", Set.of("description", "attributes.brand",
                        "attributes.net_weight", "attributes.servings", "attributes.shelf",
                        "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("PER", "dairy-eggs", Set.of("description", "attributes.brand",
                        "attributes.net_weight", "attributes.shelf", "attributes.storage",
                        "variants[0].packaging")),
                Arguments.of("SFD", "prepared-meals", Set.of("description", "attributes.net_weight",
                        "attributes.servings", "attributes.shelf", "attributes.storage", "attributes.ingredients",
                        "attributes.allergens", "variants[0].packaging")),
                Arguments.of("BBY", "baby-bottles", Set.of("description", "labelPhoto", "attributes.brand",
                        "attributes.net_weight", "attributes.servings", "attributes.shelf",
                        "attributes.age_grade", "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("BEA", "skincare-cosmetics", Set.of("description", "labelPhoto", "dangerousGoods",
                        "attributes.brand", "attributes.net_weight", "attributes.ingredients",
                        "attributes.shelf", "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("HLT", "dietary-supplements", Set.of("description", "labelPhoto", "dangerousGoods",
                        "attributes.brand", "attributes.net_weight", "attributes.servings",
                        "attributes.shelf", "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("FSH", "adult-clothing-footwear", Set.of("description", "attributes.brand",
                        "attributes.material", "attributes.age_grade", "variants[0].gtin",
                        "variants[0].packaging")),
                Arguments.of("JWL", "jewelry-accessories", Set.of("description", "attributes.brand",
                        "attributes.material", "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("ELC", "phones", Set.of("description", "dangerousGoods", "attributes.brand",
                        "attributes.model", "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("HOM", "bedding-linens", Set.of("description", "dangerousGoods", "attributes.brand",
                        "attributes.material", "attributes.model", "variants[0].gtin",
                        "variants[0].packaging")),
                Arguments.of("KID", "toys-games", Set.of("description", "dangerousGoods", "attributes.brand",
                        "attributes.age_grade", "variants[0].gtin", "variants[0].packaging")),
                Arguments.of("PET", "pet-food", Set.of("description", "labelPhoto", "attributes.brand",
                        "attributes.net_weight", "attributes.shelf", "variants[0].gtin",
                        "variants[0].packaging")),
                Arguments.of("BOK", "printed-books", Set.of("description", "variants[0].gtin",
                        "variants[0].packaging")),
                Arguments.of("DIG", "downloadable-ebooks", Set.of("description", "attributes.brand")),
                Arguments.of("GFT", "gift-cards", Set.of("description", "attributes.brand")),
                Arguments.of("SRV", "repairs", Set.of("description")));
    }

    @ParameterizedTest(name = "{0}: {1} needs {2}")
    @MethodSource("kinds")
    void anEmptyListingIsRefusedForExactlyWhatTheMatrixRequires(String kind, String category, Set<String> expected) {
        CategoryRules rules = RulesFixtures.book().forCategory(category).orElseThrow();
        assertThat(rules.kindCode()).isEqualTo(kind);

        assertThat(new TreeSet<>(errors(rules, empty(rules)).keySet())).isEqualTo(new TreeSet<>(expected));
    }

    @ParameterizedTest(name = "{0}: {1} is accepted when complete")
    @MethodSource("kinds")
    void aListingWithEverythingTheMatrixAsksForIsAccepted(String kind, String category, Set<String> ignored) {
        CategoryRules rules = RulesFixtures.book().forCategory(category).orElseThrow();

        assertThat(errors(rules, complete(rules))).isEmpty();
    }

    @Test
    void everyCategoryOfTheMatrixAcceptsItsCompleteListing() {
        RulesFixtures.book().categories().values().forEach(c ->
                assertThat(errors(c, complete(c))).as(c.code()).isEmpty());
    }

    @Test
    void allergensAreATickListOfKnownValues() {
        CategoryRules rules = RulesFixtures.book().forCategory("prepared-meals").orElseThrow();
        ProductDraft draft = complete(rules);
        Map<String, String> attrs = new HashMap<>(draft.attributes());
        attrs.put("allergens", "Contains kryptonite");

        assertThat(errors(rules, withAttributes(draft, attrs))).containsKey("attributes.allergens");
        attrs.put("allergens", "Contains milk, Contains eggs");
        assertThat(errors(rules, withAttributes(draft, attrs))).doesNotContainKey("attributes.allergens");
    }

    @Test
    void aRequiredOrExemptionFieldAcceptsAReasonInsteadOfAnAnswer() {
        CategoryRules rules = RulesFixtures.book().forCategory("bedding-linens").orElseThrow();
        ProductDraft draft = complete(rules);
        Map<String, String> attrs = new HashMap<>(draft.attributes());
        attrs.remove("material");
        attrs.remove("model");

        assertThat(errors(rules, withAttributes(draft, attrs))).containsKeys("attributes.material", "attributes.model");

        ListingDetails reasons = draft.details().withRuleAnswers(false, List.of(),
                Map.of("material", "Mixed handmade fabric", "model_number", "Handmade, no model"));
        ProductDraft excused = new ProductDraft(draft.productType(), draft.title(), attrs, draft.images(),
                draft.variationTheme(), draft.variants(), reasons);
        assertThat(errors(rules, excused)).doesNotContainKeys("attributes.material", "attributes.model");
    }

    @Test
    void aRequiredBarcodeCannotBeExemptedButARequiredOrExemptionOneCan() {
        CategoryRules electronics = RulesFixtures.book().forCategory("phones").orElseThrow();
        CategoryRules general = RulesFixtures.book().forCategory("bedding-linens").orElseThrow();

        assertThat(errors(electronics, withBarcode(complete(electronics), null, true)))
                .containsEntry("variants[0].gtin", "A barcode is required for this kind of product");
        assertThat(errors(general, withBarcode(complete(general), null, true))).doesNotContainKey("variants[0].gtin");
    }

    @Test
    void anOptionalBarcodeMayBeLeftOutButMustBeValidWhenGiven() {
        CategoryRules perishable = RulesFixtures.book().forCategory("fresh-produce").orElseThrow();

        assertThat(errors(perishable, withBarcode(complete(perishable), "", false))).doesNotContainKey("variants[0].gtin");
        assertThat(errors(perishable, withBarcode(complete(perishable), "4006381333932", false)))
                .containsKey("variants[0].gtin");
    }

    @Test
    void everyPhysicalVariantNeedsItsOwnPackageWithinTheLimits() {
        CategoryRules rules = RulesFixtures.book().forCategory("sports-goods").orElseThrow();
        ProductType sized = RuleAwareType.apply(new ProductType("t", "T", List.of(), List.of(new VariationTheme("Size",
                List.of(new VariationDimension("Size", List.of("S", "M"))))), IdentifierRule.GTIN), rules);
        ProductDraft draft = complete(rules);
        VariantDraft small = new VariantDraft(Map.of("Size", "S"), "SKU-S", GTIN, false, 999, 5).withPackaging(BOX);
        VariantDraft medium = new VariantDraft(Map.of("Size", "M"), "SKU-M", "4006381333931", false, 999, 5);
        ProductDraft twoVariants = new ProductDraft(sized, draft.title(), draft.attributes(), draft.images(), "Size",
                List.of(small, medium), draft.details());

        assertThat(errors(rules, twoVariants)).containsKeys("variants[1].packaging").doesNotContainKey("variants[0].packaging");
    }

    @Test
    void packageLimitsStayAt108InchesAnd150Pounds() {
        CategoryRules rules = RulesFixtures.book().forCategory("sports-goods").orElseThrow();
        ProductDraft draft = complete(rules);

        assertThat(errors(rules, withPackage(draft, new PackageDimensions(109, 8, 4, 2))))
                .containsKey("variants[0].packaging");
        assertThat(errors(rules, withPackage(draft, new PackageDimensions(10, 8, 4, 151))))
                .containsKey("variants[0].packaging");
        assertThat(errors(rules, withPackage(draft, new PackageDimensions(108, 108, 108, 150)))).isEmpty();
    }

    @Test
    void groceriesDoNotRequireALabelPhotoButHealthBeautyBabyFeedingAndPetFoodStill() {
        for (String groceries : List.of("packaged-snacks", "dairy-eggs", "beans-pantry-sauces")) {
            CategoryRules rules = RulesFixtures.book().forCategory(groceries).orElseThrow();
            assertThat(rules.status(RuleField.LABEL_PHOTO)).as(groceries).isEqualTo(FieldStatus.OPTIONAL);
            ProductDraft noLabel = complete(rules);
            assertThat(errors(rules, noLabel)).as(groceries).isEmpty();
        }
        for (String regulated : List.of("dietary-supplements", "skincare-cosmetics", "baby-bottles", "pet-food")) {
            CategoryRules rules = RulesFixtures.book().forCategory(regulated).orElseThrow();
            assertThat(rules.status(RuleField.LABEL_PHOTO).expectsAnswer()).as(regulated).isTrue();
        }
    }

    @Test
    void theLabelPhotoIsATypedSlotThatTheProductPicturesDoNotFill() {
        CategoryRules rules = RulesFixtures.book().forCategory("dietary-supplements").orElseThrow();
        ProductDraft draft = complete(rules);
        ProductDraft onlyPictures = new ProductDraft(draft.productType(), draft.title(), draft.attributes(),
                List.of(IMAGE, IMAGE), draft.variationTheme(), draft.variants(), draft.details());

        assertThat(errors(rules, onlyPictures)).containsOnlyKeys("labelPhoto");
    }

    @Test
    void aLabelPhotoAloneIsNotAProductPicture() {
        CategoryRules rules = RulesFixtures.book().forCategory("dietary-supplements").orElseThrow();
        ProductDraft draft = complete(rules);
        ProductDraft onlyLabel = new ProductDraft(draft.productType(), draft.title(), draft.attributes(),
                List.of(LABEL), draft.variationTheme(), draft.variants(), draft.details());

        assertThat(errors(rules, onlyLabel)).containsOnlyKeys("images");
    }

    // ------------------------------------------------------------------ helpers

    private static ProductType typeFor(CategoryRules rules) {
        IdentifierRule id = rules.kindCode().equals("BOK") ? IdentifierRule.ISBN : IdentifierRule.GTIN;
        return RuleAwareType.apply(new ProductType("t", "T", List.of(), List.of(), id), rules);
    }

    private static Map<String, String> errors(CategoryRules rules, ProductDraft draft) {
        try {
            POLICY.validate(draft, rules);
            return Map.of();
        } catch (CatalogRuleViolation e) {
            return e.fieldErrors();
        }
    }

    /** Valid title, one image, one priced variant, nothing else: only what the matrix requires is missing. */
    private static ProductDraft empty(CategoryRules rules) {
        return new ProductDraft(typeFor(rules), "A valid product title", Map.of(), List.of(IMAGE), "",
                List.of(new VariantDraft(Map.of(), "SKU-1", "", false, 999, 5)), ListingDetails.DEFAULT);
    }

    /** Everything the category's rules ask for. */
    private static ProductDraft complete(CategoryRules rules) {
        Map<String, String> attrs = new HashMap<>();
        List<ProductImage> images = new ArrayList<>(List.of(IMAGE));
        for (FieldRule r : rules.fields()) {
            if (!r.status().shown()) {
                continue;
            }
            RuleField f = r.field();
            if (f.attributeKey() != null && r.status().expectsAnswer() && f != RuleField.BRAND) {
                attrs.put(f.attributeKey(), sample(f));
            }
        }
        if (rules.status(RuleField.BRAND).expectsAnswer()) {
            attrs.put("brand", "Mama Kano");
        }
        if (rules.status(RuleField.LABEL_PHOTO).expectsAnswer()) {
            images.add(LABEL);
        }
        boolean barcode = rules.status(RuleField.PRODUCT_ID).expectsAnswer();
        String code = rules.kindCode().equals("BOK") ? ISBN : GTIN;
        VariantDraft v = new VariantDraft(Map.of(), "SKU-1", barcode ? code : "", false, 999, 5)
                .withPackaging(rules.physical() ? BOX : null);
        Boolean dangerous = rules.status(RuleField.DANGEROUS_GOODS).expectsAnswer() ? Boolean.FALSE : null;
        ListingDetails details = ListingDetails.DEFAULT.withRuleAnswers(dangerous, List.of(), Map.of());
        details = new ListingDetails("Shopper description", details.condition(), details.keywords(), details.fulfilment(),
                null, details.handlingDays(), null, null, null, null, dangerous, List.of(), Map.of());
        return new ProductDraft(typeFor(rules), "A valid product title", attrs, images, "", List.of(v), details);
    }

    private static String sample(RuleField f) {
        return switch (f) {
            case NET_WEIGHT -> "12 oz";
            case SHELF_LIFE -> "12 months";
            case SERVINGS -> "4";
            case STORAGE -> "Room temperature";
            case ALLERGENS -> "None";
            default -> "Sample";
        };
    }

    private static ProductDraft withAttributes(ProductDraft d, Map<String, String> attrs) {
        return new ProductDraft(d.productType(), d.title(), attrs, d.images(), d.variationTheme(), d.variants(), d.details());
    }

    private static ProductDraft withBarcode(ProductDraft d, String gtin, boolean exempt) {
        VariantDraft v = d.variants().getFirst();
        return new ProductDraft(d.productType(), d.title(), d.attributes(), d.images(), d.variationTheme(),
                List.of(new VariantDraft(v.options(), v.sellerSku(), gtin, exempt, v.priceMinor(), v.quantity())
                        .withPackaging(v.packaging())), d.details());
    }

    private static ProductDraft withPackage(ProductDraft d, PackageDimensions p) {
        return new ProductDraft(d.productType(), d.title(), d.attributes(), d.images(), d.variationTheme(),
                List.of(d.variants().getFirst().withPackaging(p)), d.details());
    }
}
