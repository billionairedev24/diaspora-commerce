package com.afrotransact.catalog.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** The invariant the persistence adapter enforces on every write: LIVE only with a resolved decision on every variant. */
class LiveRequiresTaxTest {

    private static final TaxDecision DECISION = new TaxDecision("TAXABLE", "AT.GENERAL.X", "S001", "8.25%", "v1", "r");

    private static Variant variant(String atin, TaxStatus status, TaxDecision decision) {
        return new Variant(UUID.randomUUID(), new Atin(atin), "SKU-" + atin, null, true, Map.of(), 100, "USD", 1, null,
                List.of(), false, new VariantTax(Map.of(), status, List.of(), decision));
    }

    private static Product product(ProductStatus status, Variant... variants) {
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org"), null,
                null, "packaged-snacks", "Plantain chips", Map.of(), List.<ProductImage>of(), "", List.of(variants), status,
                null, Instant.now(), Instant.now(), 0);
    }

    @Test
    void aLiveProductWithEveryVariantResolvedIsAllowed() {
        Product p = product(ProductStatus.LIVE, variant("AT00000011", TaxStatus.READY, DECISION));
        assertThat(LiveRequiresTax.holds(p)).isTrue();
        assertThatCode(() -> LiveRequiresTax.require(p)).doesNotThrowAnyException();
    }

    @Test
    void aLiveProductWithAnyVariantUnresolvedIsRefusedWithAPlainMessagePerVariant() {
        Product p = product(ProductStatus.LIVE, variant("AT00000011", TaxStatus.READY, DECISION),
                variant("AT00000012", TaxStatus.MISSING_FACTS, null));
        assertThat(LiveRequiresTax.holds(p)).isFalse();
        assertThatThrownBy(() -> LiveRequiresTax.require(p)).isInstanceOfSatisfying(TaxNotReady.class,
                e -> assertThat(e.fieldErrors()).containsKey("variants[1].tax"));
    }

    @Test
    void everyGateStatusOtherThanReadyBlocksLive() {
        for (TaxStatus status : TaxStatus.values()) {
            if (status == TaxStatus.READY) {
                continue;
            }
            Product p = product(ProductStatus.LIVE, variant("AT00000011", status, DECISION));
            assertThat(LiveRequiresTax.holds(p)).as(status.name()).isFalse();
        }
    }

    @Test
    void readyWithoutAStoredDecisionIsNotAResolvedDecision() {
        assertThat(LiveRequiresTax.holds(product(ProductStatus.LIVE, variant("AT00000011", TaxStatus.READY, null)))).isFalse();
    }

    @Test
    void aLiveProductWithoutVariantsIsRefused() {
        assertThat(LiveRequiresTax.holds(product(ProductStatus.LIVE))).isFalse();
    }

    @Test
    void aProductThatIsNotLiveNeedsNothing() {
        for (ProductStatus status : ProductStatus.values()) {
            if (status != ProductStatus.LIVE) {
                assertThat(LiveRequiresTax.holds(product(status, variant("AT00000011", TaxStatus.MISSING_FACTS, null))))
                        .as(status.name()).isTrue();
            }
        }
    }

    @Test
    void theReasonCodeNamesTheMostPressingStatus() {
        Product p = product(ProductStatus.LIVE, variant("AT00000011", TaxStatus.PENDING_REVIEW, DECISION),
                variant("AT00000012", TaxStatus.MISSING_FACTS, null));
        assertThat(LiveRequiresTax.reasonCode(p)).isEqualTo("TAX_MISSING_FACTS");
    }

    @Test
    void holdingAndRestoringAreTheOnlyWaysBackAndForthAndNeedTheRightStatus() {
        Product live = product(ProductStatus.LIVE, variant("AT00000011", TaxStatus.PENDING_REVIEW, DECISION));
        Product held = live.holdForTax(Instant.now());
        assertThat(held.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(held.moderationNote()).isEqualTo("We are checking the tax details for this item.");
        assertThat(held.restoreAfterTaxHold(Instant.now()).status()).isEqualTo(ProductStatus.LIVE);
        assertThatThrownBy(() -> held.holdForTax(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> live.restoreAfterTaxHold(Instant.now())).isInstanceOf(IllegalStateException.class);
    }
}
