package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DefaultProductDraftPolicyTest {

    private final ProductDraftPolicy policy = new DefaultProductDraftPolicy();

    private static final ProductType BOOK = new ProductType("book", "Books",
            List.of(new AttributeDefinition("author", "Author", List.of(), true, false),
                    new AttributeDefinition("language", "Language", List.of("English", "Yoruba"), true, false)),
            List.of(new VariationTheme("Format", List.of(new VariationDimension("Format",
                    List.of("Hardcover", "Paperback"))))),
            IdentifierRule.ISBN);

    private static final ProductImage BIG = new ProductImage("https://utfs.io/f/a.jpg", 1200, 1200, 200_000,
            "image/jpeg");

    @Test
    void acceptsCompleteBook() {
        policy.validate(new ProductDraft(BOOK, "Pepper & Palm: A West African Kitchen",
                Map.of("author", "Ifeoma Ade", "language", "English"), List.of(BIG), "Format",
                List.of(new VariantDraft(Map.of("Format", "Hardcover"), "PEPPER-HC", "9780306406157", false, 3499, 5))));
    }

    @Test
    void reportsEveryFailingField() {
        ProductImage small = new ProductImage("https://utfs.io/f/b.jpg", 600, 600, 90_000, "image/jpeg");
        assertThatThrownBy(() -> policy.validate(new ProductDraft(BOOK, "Short", Map.of("language", "Klingon"),
                List.of(small), "Format", List.of(
                        new VariantDraft(Map.of("Format", "Hardcover"), "SKU-1", "4006381333931", false, 0, 1),
                        new VariantDraft(Map.of("Format", "Paperback"), "sku-1", "", false, 999, 1)))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKeys(
                        "title", "attributes.author", "attributes.language", "images[0]",
                        "variants[0].gtin", "variants[0].price", "variants[1].sellerSku", "variants[1].gtin"));
    }

    @Test
    void gtinExemptionIsNotAllowedForBooks() {
        assertThatThrownBy(() -> policy.validate(new ProductDraft(BOOK, "Pepper & Palm: A West African Kitchen",
                Map.of("author", "A", "language", "English"), List.of(BIG), "Format",
                List.of(new VariantDraft(Map.of("Format", "Hardcover"), "PEPPER-HC", null, true, 3499, 5)))))
                .isInstanceOf(CatalogRuleViolation.class);
    }
}
