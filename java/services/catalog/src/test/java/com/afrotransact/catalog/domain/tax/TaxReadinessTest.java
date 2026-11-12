package com.afrotransact.catalog.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import org.junit.jupiter.api.Test;

class TaxReadinessTest {

    private static Variant variant(String atin, VariantTax tax) {
        return new Variant(UUID.randomUUID(), new Atin(atin), "SKU-" + atin, null, true, Map.of(), 100, "USD", 1, null,
                List.of(), false, tax);
    }

    private static Product product(Variant... variants) {
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org"), null,
                null, "packaged-snacks", "Plantain chips 8 oz", Map.of(), List.<ProductImage>of(), "", List.of(variants),
                ProductStatus.IN_REVIEW, null, Instant.now(), Instant.now(), 0);
    }

    @Test
    void aProductIsReadyOnlyWhenEveryVariantIs() {
        Product p = product(variant("AT00000011", new VariantTax(Map.of(), TaxStatus.READY, List.of(), new TaxDecision("EXEMPT", "AT.X", "S001", "0%", "v1", "r"))),
                variant("AT00000012", new VariantTax(Map.of(), TaxStatus.PENDING_REVIEW, List.of(), null)));
        TaxReadiness r = TaxReadiness.of(p, "packaged-snacks", "ACTIVE");
        assertThat(r.ready()).isFalse();
        assertThat(r.status()).isEqualTo(TaxStatus.PENDING_REVIEW);
        assertThat(r.blockers()).containsKey("variants[1].tax");
        assertThat(TaxReadiness.of(product(variant("AT00000011", new VariantTax(Map.of(), TaxStatus.READY, List.of(), new TaxDecision("EXEMPT", "AT.X", "S001", "0%", "v1", "r")))),
                "packaged-snacks", "ACTIVE").ready()).isTrue();
    }

    @Test
    void theMostPressingStatusWinsAndMissingFactsAreListedPerField() {
        Product p = product(variant("AT00000011", new VariantTax(Map.of(), TaxStatus.MISSING_FACTS,
                List.of(new MissingTaxFact("net_weight_oz", "Net weight (oz)", "Net weight (oz) is needed.")), null)),
                variant("AT00000012", new VariantTax(Map.of(), TaxStatus.REFINE_CODE, List.of(), null)));
        TaxReadiness r = TaxReadiness.of(p, "toys-games", "REFINE_CODE");
        assertThat(r.status()).isEqualTo(TaxStatus.MISSING_FACTS);
        assertThat(r.blockers()).containsEntry("variants[0].taxFacts.net_weight_oz", "Net weight (oz) is needed.");
        assertThat(r.categoryMessage()).contains("extra check");
    }

    @Test
    void aProductWithoutVariantsIsNeverReady() {
        assertThat(TaxReadiness.of(product(), null, null).ready()).isFalse();
    }

    @Test
    void gatesExplainThemselvesInPlainWords() {
        assertThat(TaxGate.message("NEEDS_APPROVAL")).contains("save your product as a draft");
        assertThat(TaxGate.message("ACTIVE")).isNull();
        assertThat(TaxGate.closed("CONFLICT_OPEN")).isTrue();
        assertThat(TaxGate.closed("REFINE_CODE")).isFalse();
        for (TaxStatus s : TaxStatus.values()) {
            assertThat(TaxGate.variantMessage(s)).isNotBlank();
        }
    }
}
