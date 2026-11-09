package com.afrotransact.taxreturns.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import com.afrotransact.taxreturns.application.VariantClassificationService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CatalogTaxFactsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final CatalogTaxFactsListener listener = new CatalogTaxFactsListener(
            mock(VariantClassificationService.class), json);

    @Test
    void theEventBecomesACommandWithTypedFactValues() {
        VariantClassificationService.CatalogFactsChanged e = listener.parse(json.readTree("""
                {"productId":"6f1f5c0e-1111-4222-8333-444455556666","ownerOrgId":"org_1","categoryCode":"nuts-seeds",
                 "productTypeCode":"nuts-seeds","sellerBehaviour":{"sold_heated":false},"changedBy":"seller_1",
                 "occurredAt":"2026-11-09T10:00:00Z",
                 "variants":[{"atin":"AT12345678","gtin":null,"facts":{"is_candy":false,"net_weight_oz":8.5,
                    "servings_per_package":4,"label_type":"PLAIN","note":null},"changedFactIds":["net_weight_oz"],
                    "evidenceChanged":true}]}
                """));

        assertThat(e.categoryCode()).isEqualTo("nuts-seeds");
        assertThat(e.ownerOrgId()).isEqualTo("org_1");
        assertThat(e.sellerBehaviour()).containsEntry("sold_heated", false);
        assertThat(e.variants()).singleElement().satisfies(v -> {
            assertThat(v.atin()).isEqualTo("AT12345678");
            assertThat(v.evidenceChanged()).isTrue();
            assertThat(v.facts()).containsEntry("is_candy", false).containsEntry("servings_per_package", 4L)
                    .containsEntry("label_type", "PLAIN").doesNotContainKey("note");
            assertThat((BigDecimal) v.facts().get("net_weight_oz")).isEqualByComparingTo("8.5");
        });
    }

    @Test
    void anEventWithoutACategoryOrVariantsCarriesNothingToClassify() {
        assertThat(listener.parse(json.readTree("{\"variants\":[{\"atin\":\"AT1\",\"facts\":{}}]}"))).isNull();
        assertThat(listener.parse(json.readTree("{\"categoryCode\":\"nuts-seeds\",\"variants\":[]}"))).isNull();
    }
}
