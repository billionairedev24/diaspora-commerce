package com.afrotransact.catalog.domain.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import org.junit.jupiter.api.Test;

class BrandNamesTest {

    @Test
    void caseAccentsAndPunctuationDoNotMakeANewBrand() {
        assertThat(BrandNames.normalise("Nike")).isEqualTo(BrandNames.normalise("NIKE"))
                .isEqualTo(BrandNames.normalise(" n.i.k.e "));
        assertThat(BrandNames.normalise("Levi's")).isEqualTo(BrandNames.normalise("Levis"))
                .isEqualTo(BrandNames.normalise("Levi’s")).isEqualTo("levis");
        assertThat(BrandNames.normalise("Nestlé")).isEqualTo("nestle");
        assertThat(BrandNames.normalise("Dark & Lovely")).isEqualTo("darklovely");
        assertThat(BrandNames.normalise("Ẹ̀dé")).isEqualTo("ede");
        assertThat(BrandNames.normalise("نايك")).isNotEmpty();
        assertThat(BrandNames.normalise(null)).isEmpty();
    }

    @Test
    void cleanNamesAreTrimmedAndBounded() {
        assertThat(BrandNames.clean("  Adire   Lagos ", "name")).isEqualTo("Adire Lagos");
        assertThatThrownBy(() -> BrandNames.clean("x", "name")).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> BrandNames.clean("x".repeat(121), "name")).isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> BrandNames.clean("!!!", "name")).isInstanceOfSatisfying(CatalogRuleViolation.class,
                e -> assertThat(e.fields()).containsExactly("name"));
    }

    @Test
    void noBrandWordsAreReasonsNotBrands() {
        assertThat(UnbrandedReason.ofText("Generic")).contains(UnbrandedReason.UNBRANDED);
        assertThat(UnbrandedReason.ofText("No brand")).contains(UnbrandedReason.UNBRANDED);
        assertThat(UnbrandedReason.ofText("N/A")).contains(UnbrandedReason.UNBRANDED);
        assertThat(UnbrandedReason.ofText("Hand-made")).contains(UnbrandedReason.HANDMADE);
        assertThat(UnbrandedReason.ofText("Genericon")).isEmpty();
        assertThat(UnbrandedReason.ofText("Nike")).isEmpty();
        assertThat(UnbrandedReason.ofCode("handmade")).contains(UnbrandedReason.HANDMADE);
        assertThat(UnbrandedReason.ofCode("Bogus")).isEmpty();
    }

    @Test
    void aTypeAsksForABrandOnlyThroughItsDefinition() {
        AttributeDefinition brand = new AttributeDefinition("brand", "Brand or label", List.of("UNBRANDED", "bogus"),
                true, false, AttributeKind.BRAND, List.of(), AttributeSection.DETAILS, "The label", true);
        ProductType fashion = new ProductType("dress", "Dresses", List.of(brand), List.of(), IdentifierRule.GTIN);
        assertThat(fashion.brandAttribute()).contains(brand);
        assertThat(brand.unbrandedReasons()).containsExactly(UnbrandedReason.UNBRANDED);
        assertThat(brand.problem(null)).isEqualTo("Required");
        assertThat(brand.problem("Nike")).isNull();
        assertThat(brand.problem("x".repeat(121))).isEqualTo("Too long");

        ProductType book = new ProductType("book", "Books", List.of(new AttributeDefinition("author", "Author",
                List.of(), true, false)), List.of(), IdentifierRule.ISBN);
        assertThat(book.brandAttribute()).isEmpty();

        AttributeDefinition off = new AttributeDefinition("brand", "Brand", List.of(), false, false,
                AttributeKind.BRAND, List.of(), AttributeSection.DETAILS, null, false);
        assertThat(new ProductType("x", "X", List.of(off), List.of(), IdentifierRule.GTIN).brandAttribute()).isEmpty();
    }

    @Test
    void aBrandCanBePickedByItsAuthorOrByEveryoneOnceApproved() {
        Brand pending = Brand.create("Cotedivoire", BrandStatus.PENDING, "org_a", Set.of(), Instant.EPOCH);
        assertThat(pending.offeredTo("org_a")).isTrue();
        assertThat(pending.offeredTo("org_b")).isFalse();
        assertThat(pending.offeredTo(null)).isFalse();
        Brand approved = pending.decided(BrandStatus.APPROVED, null, "stf", "ok", Instant.EPOCH);
        assertThat(approved.offeredTo("org_b")).isTrue();
        assertThat(approved.decided(BrandStatus.REJECTED, null, "stf", "no", Instant.EPOCH).offeredTo("org_a"))
                .isFalse();
    }
}
