package com.afrotransact.catalog.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SellerSkusTest {

    @Test
    void theGeneratedSkuIsStableOrganisationPrefixedAndValid() {
        String sku = SellerSkus.generate("org_adire", "AT00001234");
        assertThat(sku).isEqualTo(SellerSkus.generate("org_adire", "AT00001234"));
        assertThat(sku).matches("[A-Z0-9]{4}-AT00001234");
        assertThat(SellerSkus.VALID.matcher(sku).matches()).isTrue();
        assertThat(sku).startsWith(SellerSkus.prefix("org_adire") + "-");
    }

    @Test
    void differentOrganisationsGetDifferentPrefixesForTheSameAtin() {
        assertThat(SellerSkus.prefix("org_adire")).isNotEqualTo(SellerSkus.prefix("org_lagos_spice"));
        assertThat(SellerSkus.prefix(null)).hasSize(4);
    }

    @Test
    void aCollisionGetsANumericSuffixAndStaysValid() {
        String sku = SellerSkus.withSuffix(SellerSkus.generate("org_a", "AT00000001"), 2);
        assertThat(sku).endsWith("-2");
        assertThat(SellerSkus.VALID.matcher(sku).matches()).isTrue();
    }

    @Test
    void aTypedSkuIsTrimmedAndUpperCased() {
        assertThat(SellerSkus.normalise("  ab-1 ")).isEqualTo("AB-1");
        assertThat(SellerSkus.normalise(null)).isEmpty();
    }
}
