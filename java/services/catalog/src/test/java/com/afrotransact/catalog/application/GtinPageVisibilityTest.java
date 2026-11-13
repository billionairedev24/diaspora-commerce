package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;

/** A GTIN prefill or check never names another seller's page that is not live. */
class GtinPageVisibilityTest {

    private static Product page(ProductStatus status, String ownerOrg) {
        Product p = mock(Product.class);
        when(p.status()).thenReturn(status);
        when(p.owner()).thenReturn(new ListingOwner(ListingOwnerKind.SELLER, ownerOrg));
        return p;
    }

    private static Actor seller(String org) {
        return new Actor("u-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("catalog:write"));
    }

    @Test
    void livePagesAreVisibleToEverySeller() {
        assertThat(GtinPageVisibility.visibleTo(page(ProductStatus.LIVE, "org-b"), seller("org-a"))).isTrue();
    }

    @Test
    void nonLivePagesAreVisibleOnlyToTheirOwnerAndStaff() {
        for (ProductStatus status : ProductStatus.values()) {
            if (status == ProductStatus.LIVE) {
                continue;
            }
            Product ofB = page(status, "org-b");
            assertThat(GtinPageVisibility.visibleTo(ofB, seller("org-a"))).as(status + " for another seller").isFalse();
            assertThat(GtinPageVisibility.visibleTo(ofB, seller("org-b"))).as(status + " for its owner").isTrue();
            assertThat(GtinPageVisibility.visibleTo(ofB, new Actor("stf", PrincipalType.STAFF, Optional.empty(),
                    Set.of(), Set.of("catalog:read")))).as(status + " for staff").isTrue();
        }
        Actor noOrg = new Actor("c-1", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of("catalog:read"));
        assertThat(GtinPageVisibility.visibleTo(page(ProductStatus.IN_REVIEW, "org-b"), noOrg)).isFalse();
    }
}
