package com.afrotransact.catalog.domain.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.IdentifierRule;
import org.junit.jupiter.api.Test;

class TaxonomyRulesTest {

    private static final ProductTypeTraits FOOD = new ProductTypeTraits(true, false, false, false);

    private static AttributeDefinition text(String key, boolean required) {
        return new AttributeDefinition(key, "Label " + key, List.of(), required, false);
    }

    @Test
    void slugsFoldAccentsAndPunctuation() {
        assertThat(TaxonomyRules.slug("Crème & oils")).isEqualTo("creme-oils");
        assertThat(TaxonomyRules.slug("  Women's  tops / wrappers ")).isEqualTo("women-s-tops-wrappers");
        assertThat(TaxonomyRules.slug("!!!")).isEmpty();
        assertThat(TaxonomyRules.slug("x".repeat(200))).hasSize(120);
        assertThat(TaxonomyRules.typeCodeFrom("Hair care")).isEqualTo("hair_care");
        assertThat(TaxonomyRules.typeCodeFrom("123 things")).isEqualTo("type_123_things");
    }

    @Test
    void reasonsAreRequiredAndBounded() {
        assertThat(TaxonomyRules.reason("  Launch ")).isEqualTo("Launch");
        assertThatThrownBy(() -> TaxonomyRules.reason(" ab ")).isInstanceOfSatisfying(CatalogRuleViolation.class,
                e -> assertThat(e.fields()).containsExactly("reason"));
        assertThatThrownBy(() -> TaxonomyRules.reason(null)).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> TaxonomyRules.reason("x".repeat(1001))).isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void namesAreTidyAndCannotContainThePathSeparator() {
        assertThat(TaxonomyRules.name("  Hair   care ", "name")).isEqualTo("Hair care");
        assertThatThrownBy(() -> TaxonomyRules.name("A › B", "name")).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> TaxonomyRules.name("x", "name")).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> TaxonomyRules.name("y".repeat(81), "name")).isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void codesFollowTheirFormats() {
        assertThat(TaxonomyRules.nodeCode("fashion-women")).isEqualTo("fashion-women");
        assertThatThrownBy(() -> TaxonomyRules.nodeCode("Fashion Women")).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> TaxonomyRules.nodeCode("-x")).isInstanceOf(CatalogRuleViolation.class);
        assertThat(TaxonomyRules.typeCode("hair_care")).isEqualTo("hair_care");
        assertThatThrownBy(() -> TaxonomyRules.typeCode("Hair")).isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void theTreeHasThreeLevels() {
        assertThat(TaxonomyRules.parentLevel(CategoryLevel.DEPARTMENT)).isNull();
        assertThat(TaxonomyRules.parentLevel(CategoryLevel.CATEGORY)).isEqualTo(CategoryLevel.DEPARTMENT);
        assertThat(TaxonomyRules.parentLevel(CategoryLevel.PRODUCT_TYPE)).isEqualTo(CategoryLevel.CATEGORY);
        assertThat(TaxonomyRules.pathOf(null, "Fashion")).isEqualTo("Fashion");
        assertThat(TaxonomyRules.pathOf("Fashion", "Women")).isEqualTo("Fashion › Women");
    }

    @Test
    void perishableMeansFood() {
        assertThat(TaxonomyRules.traits(null)).isEqualTo(ProductTypeTraits.NONE);
        assertThatThrownBy(() -> TaxonomyRules.traits(new ProductTypeTraits(false, true, false, false)))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void attributesAreNormalisedAndChecked() {
        List<AttributeDefinition> ok = TaxonomyRules.attributes(List.of(
                new AttributeDefinition("brand", " Brand ", List.of(), true, false),
                new AttributeDefinition("age", "Age", List.of("3+", " 8+ "), false, false)), ProductTypeTraits.NONE, null);
        assertThat(ok.get(0).label()).isEqualTo("Brand");
        assertThat(ok.get(1).options()).containsExactly("3+", "8+");
        assertThat(ok.get(1).kind()).isEqualTo(AttributeKind.ENUM);
    }

    @Test
    void anAttributeThatDoesNotApplyIsNeverRequired() {
        List<AttributeDefinition> out = TaxonomyRules.attributes(List.of(new AttributeDefinition("brand", "Brand",
                List.of(), true, false, AttributeKind.TEXT, List.of(), AttributeSection.DETAILS, null, false)),
                ProductTypeTraits.NONE, null);
        assertThat(out.getFirst().applies()).isFalse();
        assertThat(out.getFirst().required()).isFalse();
    }

    @Test
    void attributeProblemsNameTheirFields() {
        assertThatThrownBy(() -> TaxonomyRules.attributes(List.of(text("Bad Key", false), text("ok", false),
                text("ok", false),
                new AttributeDefinition("choice", "Choice", List.of(), false, false, AttributeKind.ENUM, List.of(),
                        AttributeSection.DETAILS, null),
                new AttributeDefinition("unit", "Unit", List.of(), false, false, AttributeKind.TEXT, List.of("kg"),
                        AttributeSection.DETAILS, null),
                new AttributeDefinition("shelf", "Shelf", List.of(), false, false, AttributeKind.NUMBER,
                        List.of("days"), AttributeSection.FOOD, null)), ProductTypeTraits.NONE, null))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fields()).containsExactly(
                        "attributes[0].key", "attributes[2].key", "attributes[3].options", "attributes[4].units",
                        "attributes[5].section"));
    }

    @Test
    void foodAndRegulatedSectionsNeedTheirTraits() {
        AttributeDefinition shelf = new AttributeDefinition("shelf", "Shelf", List.of(), false, false,
                AttributeKind.NUMBER, List.of("days"), AttributeSection.FOOD, null);
        assertThat(TaxonomyRules.attributes(List.of(shelf), FOOD, null)).hasSize(1);
        AttributeDefinition permit = new AttributeDefinition("permit", "Permit", List.of(), false, false,
                AttributeKind.TEXT, List.of(), AttributeSection.REGULATED, null);
        assertThatThrownBy(() -> TaxonomyRules.attributes(List.of(permit), FOOD, null))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void theSalesTaxQuestionIsOwnedByTaxReturns() {
        AttributeDefinition tax = new AttributeDefinition("tax_class", "Which?", List.of("A", "B"), false, false);
        List<AttributeDefinition> current = List.of(tax);
        assertThatThrownBy(() -> TaxonomyRules.attributes(List.of(), ProductTypeTraits.NONE, current))
                .isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> TaxonomyRules.attributes(List.of(new AttributeDefinition("tax_class", "Which?",
                List.of("A", "C"), false, false)), ProductTypeTraits.NONE, current))
                .isInstanceOf(CatalogRuleViolation.class);
        // asking it or not, and whether it is required, can change
        assertThat(TaxonomyRules.attributes(List.of(new AttributeDefinition("tax_class", "Which?", List.of("A", "B"),
                true, false)), ProductTypeTraits.NONE, current)).hasSize(1);
    }

    @Test
    void productTypesOnlyAskForAttributesThatApply() {
        AttributeDefinition brand = new AttributeDefinition("brand", "Brand", List.of(), true, false, null, null, null,
                null, false);
        ProductType books = new ProductType("book", "Books", List.of(text("author", true), brand), List.of(),
                IdentifierRule.ISBN);
        assertThat(books.attributes()).hasSize(2);
        assertThat(books.applicableAttributes()).extracting(AttributeDefinition::key).containsExactly("author");
        assertThat(books.attribute("brand")).isEmpty();
        assertThat(books.attribute("author")).isPresent();
    }

    @Test
    void theBrandAttributeOffersOnlyKnownNoBrandChoices() {
        AttributeDefinition ok = new AttributeDefinition("brand", "Brand or label", List.of("UNBRANDED", "HANDMADE"),
                true, false, AttributeKind.BRAND, List.of(), AttributeSection.DETAILS, "The label", true);
        assertThat(TaxonomyRules.attributes(List.of(ok), ProductTypeTraits.NONE, null)).singleElement()
                .satisfies(a -> assertThat(a.unbrandedReasons()).hasSize(2));

        AttributeDefinition wrongKey = new AttributeDefinition("maker", "Maker", List.of(), false, false,
                AttributeKind.BRAND, List.of(), AttributeSection.DETAILS, null, true);
        assertThatThrownBy(() -> TaxonomyRules.attributes(List.of(wrongKey), ProductTypeTraits.NONE, null))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsKey("attributes[0].key"));
        AttributeDefinition badOption = new AttributeDefinition("brand", "Brand", List.of("GENERIC"), false, false,
                AttributeKind.BRAND, List.of(), AttributeSection.DETAILS, null, true);
        assertThatThrownBy(() -> TaxonomyRules.attributes(List.of(badOption), ProductTypeTraits.NONE, null))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsKey("attributes[0].options"));
    }
}
